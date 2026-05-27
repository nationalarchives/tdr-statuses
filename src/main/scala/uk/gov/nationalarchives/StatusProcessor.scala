package uk.gov.nationalarchives

import cats.effect.{IO, Resource}
import cats.implicits._
import uk.gov.nationalarchives.BackendCheckUtils.{File, Input, Status}
import uk.gov.nationalarchives.PuidJsonReader.AllPuidInformation
import uk.gov.nationalarchives.aws.utils.s3.S3Clients.s3Async
import uk.gov.nationalarchives.aws.utils.s3.S3Utils

class StatusProcessor(input: Input, allPuidInformation: AllPuidInformation, s3Utils: S3Utils) {

  private def fetchFileBytes(file: File): IO[Option[Array[Byte]]] = {
    def readFromS3(bucket: String, key: String): IO[Option[Array[Byte]]] =
      Resource
        .fromAutoCloseable(IO(s3Utils.getObjectAsStream(bucket, key)))
        .use(is => IO(is.readAllBytes()))
        .map(Some(_))
        .handleError(_ => None)

    (file.s3CleanDestinationBucket, file.s3CleanDestinationBucketKey) match {
      case (Some(bucket), Some(key)) => readFromS3(bucket, key)
      case _ => IO.pure(None)
    }
  }
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
  private val MultiplePuids = "MultipleFormats"
  private val ClientChecksum = "ClientChecksum"
  private val ClientFilePath = "ClientFilePath"
  private val FFIDStatus = "FFID"
  private val ServerFFID = "ServerFFID"
  private val CompletedWithIssues = "CompletedWithIssues"
  private val Completed = "Completed"
  private val ClientChecks = "ClientChecks"
  private val ServerRedaction = "ServerRedaction"

  def antivirus(): IO[List[Status]] = {
    input.results.map(result => {
      val fileCheckResults = result.fileCheckResults
      val status = if(fileCheckResults.antivirus.headOption.isEmpty) {
        Failed
      } else {
        fileCheckResults.antivirus.head.result match {
          case "" | "NO_THREATS_FOUND" => Success
          case _ => VirusDetected
        }
      }
      Status(result.fileId, FileType, Antivirus, status)
    })
  }.pure[IO]

  private val Unidentified = "Unidentified"

  def ffid(): IO[List[Status]] = {
    input.results.traverse { result =>
      val fileFormat = result.fileCheckResults.fileFormat
      val puidMatches = fileFormat.flatMap(_.matches.map(_.puid.getOrElse("")))
      val disallowedReason = allPuidInformation.disallowedPuids
        .filter(_.active)
        .filter(_.puid.nonEmpty)
        .find(r => puidMatches.contains(r.puid)).map(_.reason)
      val judgmentDisAllowedPuid = !puidMatches.forall(p => allPuidInformation.allowedPuids.map(_.puid).contains(p))

      val emptyFileChecksums: Set[String] = allPuidInformation.puidChecksums
        .filter(_.puid == "zeroByteFile")
        .flatMap(_.checksums.map(_.checksum))
        .toSet

      val serverChecksum = result.fileCheckResults.checksum.map(_.sha256Checksum).headOption
      val isEmptyFile = serverChecksum.exists(emptyFileChecksums.contains)

      val isUnidentified = puidMatches.nonEmpty && puidMatches.forall(_.isEmpty) && fileFormat.nonEmpty

      val extensionOnlyTextFile = fileFormat.flatMap(_.matches).exists { m =>
        m.identificationBasis.toLowerCase.contains("extension") &&
          m.extension.exists(ext => ext.equalsIgnoreCase("txt") || ext.equalsIgnoreCase("csv"))
      }

      result match {
        case r if r.consignmentType == "judgment" && judgmentDisAllowedPuid =>
          Status(result.fileId, FileType, FFIDStatus, NonJudgmentFormat).pure[IO]
        case _ if isEmptyFile =>
          Status(result.fileId, FileType, FFIDStatus, ZeroByteFile).pure[IO]
        case r if r.fileSize == "0" =>
          Status(result.fileId, FileType, FFIDStatus, ZeroByteFile).pure[IO]
        case _ if fileFormat.isEmpty =>
          Status(result.fileId, FileType, FFIDStatus, Failed).pure[IO]
        case _ if puidMatches.count(_.nonEmpty) > 1 =>
          Status(result.fileId, FileType, FFIDStatus, MultiplePuids).pure[IO]
        case _ if isUnidentified =>
          fetchFileBytes(result).map {
            case Some(bytes) if FileContentValidator.isAllowedContent(bytes) =>
              Status(result.fileId, FileType, FFIDStatus, Success)
            case _ =>
              Status(result.fileId, FileType, FFIDStatus, Unidentified)
          }
        case _ if extensionOnlyTextFile =>
          fetchFileBytes(result).map {
            case Some(bytes) if FileContentValidator.isAllowedContent(bytes) =>
              Status(result.fileId, FileType, FFIDStatus, disallowedReason.getOrElse(Success))
            case Some(_) =>
              Status(result.fileId, FileType, FFIDStatus, Unidentified)
            case None =>
              Status(result.fileId, FileType, FFIDStatus, disallowedReason.getOrElse(Success))
          }
        case _ =>
          Status(result.fileId, FileType, FFIDStatus, disallowedReason.getOrElse(Success)).pure[IO]
      }
    }
  }

  def checksumMatch(): IO[List[Status]] = {
    input.results.map(result => {
      val checksumResult = result.fileCheckResults.checksum
      val serverChecksum = checksumResult.map(_.sha256Checksum).headOption
      val clientChecksum = result.clientChecksum
      val statusValue = if(checksumResult.isEmpty) {
        Failed
      } else if (serverChecksum.contains(clientChecksum)) {
        Success
      } else {
        Mismatch
      }
      Status(result.fileId, FileType, ChecksumMatch, statusValue)
    }).pure[IO]
  }

  def serverChecksum(): IO[List[Status]] = {
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
        .map(result => Status(result.consignmentId, ConsignmentType, ServerChecksum, consignmentStatus, overwrite  = true)).toList ++ fileStatuses
    }
  }

  def serverAntivirus(): IO[List[Status]] = antivirus().map(av => {
    val value = if (av.exists(_.statusValue == Failed)) {
      Failed
    } else if(av.exists(_.statusValue == VirusDetected)) {
      CompletedWithIssues
    } else {
      Completed
    }
    input.results.headOption.map(result => Status(result.consignmentId, ConsignmentType, ServerAntivirus, value, overwrite = true)).toList
  })

  def clientChecksum(): IO[List[Status]] = statusIfEmpty(res => res.clientChecksum.some, ClientChecksum)

  def clientFilePath(): IO[List[Status]] = statusIfEmpty(res => res.originalPath.some, ClientFilePath)

  def redactedStatus(): IO[List[Status]] = {
    input.redactedResults.redactedFiles.map(red => Status(red.redactedFileId, FileType, "Redaction", Success)) ++
      input.redactedResults.errors.map(err => Status(err.fileId, FileType, "Redaction", err.cause))
  }.pure[IO]

  def serverFFID(): IO[List[Status]] = {
    for {
      fileFFID <- ffid()
    } yield {
      val activeDisallowedReasons = allPuidInformation.disallowedPuids.filter(_.active).map(_.reason)
      val hasErrors = fileFFID.map(_.statusValue).exists(v => activeDisallowedReasons.contains(v) || v == MultiplePuids)
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

  def serverRedaction(): IO[List[Status]] = {
    for {
      redactedResults <- redactedStatus()
    } yield {
      val statusValue = if (redactedResults.exists(_.statusValue != Success)) { CompletedWithIssues } else Completed
      input.results.headOption.map(result => Status(result.consignmentId, ConsignmentType, ServerRedaction, statusValue, overwrite = true)).toList
      }
    }

  def fileClientChecks(): IO[List[Status]] = {
    for {
      ffid <- ffid()
      clientChecksum <- clientChecksum()
      clientFilePath <- clientFilePath()
      redactions <- redactedStatus()
    } yield {
      val allStatuses = ffid ++ clientChecksum ++ clientFilePath ++ redactions
      val failedIds = allStatuses.filter(s => {
        if(s.statusName == FFIDStatus) {
          s.statusValue == ZeroByteFile || s.statusValue == MultiplePuids
        } else {
          s.statusValue != Success
        }
      }).map(_.id).toSet
      val successfulIds = allStatuses.map(_.id).toSet.diff(failedIds)
      (failedIds.map(id => Status(id, FileType, ClientChecks, CompletedWithIssues)) ++
        successfulIds.map(id => Status(id, FileType, ClientChecks, Completed))).toList
    }
  }

  def consignmentClientChecks(): IO[List[Status]] = {
    fileClientChecks().map(checks => {
      val result = checks.find(_.statusValue == CompletedWithIssues).map(_.statusValue).getOrElse(Completed)
      input.results.headOption.map(res => {
        Status(res.consignmentId, ConsignmentType, ClientChecks, result, overwrite = true)
      }).toList
    })
  }

  private def statusIfEmpty(fn: File => Option[String], statusName: String): IO[List[Status]] = {
    input.results.map(res => {
      val value = fn(res)
      val statusValue = if (value.getOrElse("").equals("")) {
        Failed
      } else {
        Success
      }
      Status(res.fileId, FileType, statusName, statusValue)
    }).pure[IO]
  }
}

object StatusProcessor {
  private lazy val s3Utils: S3Utils = S3Utils(s3Async(sys.env("S3_ENDPOINT")))

  def apply(input: Input, allPuidInformation: AllPuidInformation): StatusProcessor =
    new StatusProcessor(input, allPuidInformation, s3Utils)
}
