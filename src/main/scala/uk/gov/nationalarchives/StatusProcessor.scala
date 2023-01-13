package uk.gov.nationalarchives

import cats.Monad
import cats.implicits._
import uk.gov.nationalarchives.Lambda.{File, Input, Status}
import uk.gov.nationalarchives.PuidRepository.AllPuidInformation

import java.util.UUID
import scala.util.Try

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
  private val ZeroByteFile = "ZeroByteFile"
  private val ClientChecksum = "ClientChecksum"
  private val ClientFilePath = "ClientFilePath"
  private val FFIDStatus = "FFID"
  private val ServerFFID = "ServerFFID"
  private val CompletedWithIssues = "CompletedWithIssues"
  private val Completed = "Completed"
  private val ClientChecks = "ClientChecks"

  def antivirus(): F[List[Status]] = {
    for {
      res <- input.results
      av <- res.fileCheckResults.antivirus
    } yield {
      val value = av.result match {
        case "" => Success
        case _ => VirusDetected
      }
      Status(res.fileId, FileType, Antivirus, value)
    }
  }.pure[F]

  def ffid(): F[List[Status]] = {
    for {
      res <- input.results
      ffid <- res.fileCheckResults.fileFormat
      matches <- ffid.matches
    } yield {
      val puidMatch = matches.puid.getOrElse("")
      val disallowedReason = allPuidInformation.disallowedPuids.find(_.puid == puidMatch).map(_.reason)
      val judgmentDisAllowedPuid = !allPuidInformation.allowedPuids.map(_.puid).contains(puidMatch)
      val reason = if (res.consignmentType == "judgment" && judgmentDisAllowedPuid) {
        NonJudgmentFormat
      } else if (res.consignmentType == "standard" && Try(res.fileSize.toLong).getOrElse(0L) > 0) {
        disallowedReason.getOrElse(Success)
      } else if (res.fileSize == "0") {
        ZeroByteFile
      } else {
        Success
      }
      Status(res.fileId, FileType, FFIDStatus, reason)
    }

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

  def serverChecksum(): F[List[Status]] =
    statusIfEmpty(res => res.fileCheckResults.checksum.map(_.sha256Checksum).headOption, ServerChecksum)

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
      input.results.headOption.map(i => {
        val statusValue = if (hasErrors) {
          CompletedWithIssues
        } else {
          Completed
        }
        Status(i.consignmentId, ConsignmentType, ServerFFID, statusValue)
      }).toList
    }
  }

  def clientChecks(): F[List[Status]] = {
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
      val successfulIds = allStatuses.filter(_.statusValue == Success).map(_.id).toSet
      (failedIds.map(id => Status(id, FileType, ClientChecks, CompletedWithIssues)) ++
        successfulIds.map(id => Status(id, FileType, ClientChecks, Completed))).toList
    }
  }

  private def statusIfEmpty(fn: File => Option[String], statusName: String): F[List[Status]] = {
    input.results.map(res => {
      val value = fn(res)
      val statusValue = if (value.contains("")) {
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
