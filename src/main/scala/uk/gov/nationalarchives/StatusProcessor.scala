package uk.gov.nationalarchives

import cats.Monad
import cats.implicits._
import uk.gov.nationalarchives.BackendCheckUtils.{File, Input, Status}
import uk.gov.nationalarchives.PuidRepository.AllPuidInformation

class StatusProcessor[F[_] : Monad](input: Input, allPuidInformation: AllPuidInformation) {
  private val Success = "Success"
  private val VirusDetected = "VirusDetected"
  private val Antivirus = "Antivirus"
  private val NonJudgmentFormat = "NonJudgmentFormat"
  private val Mismatch = "Mismatch"
  private val ChecksumMatch = "ChecksumMatch"
  private val FileType = "File"
  private val ConsignmentType = "Consignment"
  private val Failed = "Failed"
  private val ServerChecksum = "ServerChecksum"
  private val ServerAntivirus = "ServerAntivirus"
  private val ZeroByteFile = "ZeroByteFile"
  private val ClientChecksum = "ClientChecksum"
  private val ClientFilePath = "ClientFilePath"
  private val FFIDStatus = "FFID"
  private val ServerFFID = "ServerFFID"
  private val CompletedWithIssues = "CompletedWithIssues"
  private val Completed = "Completed"
  private val ClientChecks = "ClientChecks"

  def antivirus(): F[List[Status]] = {
    input.results.map(result => {
      val fileCheckResults = result.fileCheckResults
      val status = if(fileCheckResults.antivirus.headOption.isEmpty) {
        Failed
      } else {
        fileCheckResults.antivirus.head.result match {
          case "" => Success
          case _ => VirusDetected
        }
      }
      Status(result.fileId, FileType, Antivirus, status)
    })
  }.pure[F]

  def ffid(): F[List[Status]] = {
    input.results.map(result => {
      val fileFormat = result.fileCheckResults.fileFormat
      val puidMatches = fileFormat.flatMap(_.matches.map(_.puid.getOrElse("")))
      val disallowedReason = allPuidInformation.disallowedPuids
        .filter(_.active)
        .find(r => puidMatches.contains(r.puid)).map(_.reason)
      val judgmentDisAllowedPuid = !allPuidInformation.allowedPuids.map(_.puid).forall(a => puidMatches.contains(a))
      val reason = if (result.consignmentType == "judgment" && judgmentDisAllowedPuid) {
        NonJudgmentFormat
      } else if (result.fileSize == "0" && disallowedReason.contains(ZeroByteFile)) {
        ZeroByteFile
      } else if (fileFormat.isEmpty) {
        Failed
      } else {
        disallowedReason.getOrElse(Success)
      }
      Status(result.fileId, FileType, FFIDStatus, reason)
    })
  }.pure[F]

  def checksumMatch(): F[List[Status]] = {
    input.results.map(result => {
      val serverChecksum = result.fileCheckResults.checksum.map(_.sha256Checksum).headOption
      val clientChecksum = result.clientChecksum
      val statusValue = if (serverChecksum.contains(clientChecksum)) {
        Success
      } else {
        Mismatch
      }
      Status(result.fileId, FileType, ChecksumMatch, statusValue)
    }).pure[F]
  }

  def serverChecksum(): F[List[Status]] = {
    for {
      fileStatuses <- statusIfEmpty(res => res.fileCheckResults.checksum.map(_.sha256Checksum).headOption, ServerChecksum)
    } yield {
      val consignmentStatus = if (input.results.map(_.fileCheckResults).exists(_.checksum.isEmpty)) {
        Failed
      } else if (fileStatuses.exists(_.statusValue == Failed)) {
        CompletedWithIssues
      } else {
        Completed
      }
      input.results.headOption
        .map(result => Status(result.consignmentId, ConsignmentType, ServerChecksum, consignmentStatus)).toList ++ fileStatuses
    }
  }

  def serverAntivirus(): F[List[Status]] = antivirus().map(av => {
    val value = if (av.exists(_.statusValue == Failed)) {
      Failed
    } else if(av.exists(_.statusValue == VirusDetected)) {
      CompletedWithIssues
    } else {
      Completed
    }
    input.results.headOption.map(result => Status(result.consignmentId, ConsignmentType, ServerAntivirus, value, overwrite = true)).toList
  })

  def clientChecksum(): F[List[Status]] = statusIfEmpty(res => res.clientChecksum.some, ClientChecksum)

  def clientFilePath(): F[List[Status]] = statusIfEmpty(res => res.originalPath.some, ClientFilePath)

  def redactedStatus(): F[List[Status]] = {
    input.redactedResults.redactedFiles.map(red => Status(red.redactedFileId, FileType, "Redaction", Success)) ++
      input.redactedResults.errors.map(err => Status(err.fileId, FileType, "Redaction", err.cause))
  }.pure[F]

  def serverFFID(): F[List[Status]] = {
    for {
      fileFFID <- ffid()
    } yield {
      val activeDisallowedReasons = allPuidInformation.disallowedPuids.filter(_.active).map(_.reason)
      val hasErrors = fileFFID.map(_.statusValue).exists(activeDisallowedReasons.contains)
      val isFailed = fileFFID.exists(_.statusValue == Failed)
      input.results.headOption.map(i => {
        val statusValue = if(isFailed) {
          Failed
        } else if (hasErrors) {
          CompletedWithIssues
        } else {
          Completed
        }
        Status(i.consignmentId, ConsignmentType, ServerFFID, statusValue, overwrite = true)
      }).toList
    }
  }

  def fileClientChecks(): F[List[Status]] = {
    for {
      ffid <- ffid()
      clientChecksum <- clientChecksum()
      clientFilePath <- clientFilePath()
    } yield {
      val allStatuses = ffid ++ clientChecksum ++ clientFilePath
      val failedIds = allStatuses.filter(s => {
        if(s.statusName == FFIDStatus) {
          s.statusValue == ZeroByteFile
        } else {
          s.statusValue != Success
        }
      }).map(_.id).toSet
      val successfulIds = allStatuses.map(_.id).toSet.diff(failedIds)
      (failedIds.map(id => Status(id, FileType, ClientChecks, CompletedWithIssues)) ++
        successfulIds.map(id => Status(id, FileType, ClientChecks, Completed))).toList
    }
  }

  def consignmentClientChecks(): F[List[Status]] = {
    fileClientChecks().map(checks => {
      val result = checks.find(_.statusValue == CompletedWithIssues).map(_.statusValue).getOrElse(Completed)
      input.results.headOption.map(res => {
        Status(res.consignmentId, ConsignmentType, ClientChecks, result, overwrite = true)
      }).toList
    })
  }

  private def statusIfEmpty(fn: File => Option[String], statusName: String): F[List[Status]] = {
    input.results.map(res => {
      val value = fn(res)
      val statusValue = if (value.getOrElse("").equals("")) {
        Failed
      } else {
        Success
      }
      Status(res.fileId, FileType, statusName, statusValue)
    }).pure[F]
  }
}

object StatusProcessor {
  def apply[F[_] : Monad](input: Input, allPuidInformation: AllPuidInformation): F[StatusProcessor[F]] =
    new StatusProcessor[F](input, allPuidInformation).pure[F]
}
