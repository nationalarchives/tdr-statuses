package uk.gov.nationalarchives

import cats.effect.{IO, Resource}
import cats.implicits._
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import uk.gov.nationalarchives.BackendCheckUtils.{File, Input, Status}
import uk.gov.nationalarchives.PuidJsonReader.AllPuidInformation
import uk.gov.nationalarchives.aws.utils.s3.S3Clients.s3Async
import uk.gov.nationalarchives.aws.utils.s3.S3Utils
import _root_.uk.gov.nationalarchives.tdr.common.utils.statuses.StatusValues._
import _root_.uk.gov.nationalarchives.tdr.common.utils.statuses.StatusTypes._
import _root_.uk.gov.nationalarchives.tdr.common.utils.statuses.StatusScopes._

class StatusProcessor(input: Input, allPuidInformation: AllPuidInformation, s3Utils: S3Utils) {

  private implicit val logger: Logger[IO] = Slf4jLogger.getLogger[IO]

  private val fileUTF8ValidationCache: scala.collection.mutable.Map[String, Option[Boolean]] =
    scala.collection.mutable.Map.empty

  private def validateFileContent(file: File): IO[Option[Boolean]] = {
    def readFromS3(bucket: String, key: String): IO[Option[Boolean]] =
      Resource
        .fromAutoCloseable(IO.blocking(s3Utils.getObjectAsStreamingInputStream(bucket, key)))
        .use(is => IO.blocking(FileContentValidator.isAllowedContent(is)))
        .map(Some(_))
        .handleErrorWith { err =>
          Logger[IO].error(s"Failed to validate file from s3://$bucket/$key for fileId=${file.fileId}: ${err.getMessage}").as(None)
        }

    fileUTF8ValidationCache.get(file.fileId.toString) match {
      case Some(cached) => IO.pure(cached)
      case None =>
        (file.s3CleanDestinationBucket, file.s3CleanDestinationBucketKey) match {
          case (Some(bucket), Some(key)) =>
            readFromS3(bucket, key).flatTap(result => IO(fileUTF8ValidationCache.put(file.fileId.toString, result)))
          case _ => IO.pure(None)
        }
    }
  }

  def antivirus(): IO[List[Status]] = {
    input.results.map(result => {
      val fileCheckResults = result.fileCheckResults
      val status = if(fileCheckResults.antivirus.headOption.isEmpty) {
        FailedValue.value
      } else {
        fileCheckResults.antivirus.head.result match {
          case "" | "NO_THREATS_FOUND" => SuccessValue.value
          case _ => VirusDetectedValue.value
        }
      }
      Status(result.fileId, FileScope.value, AntivirusType.id, status)
    })
  }.pure[IO]

  def ffid(): IO[List[Status]] = {
    input.results.traverse { result =>
      val fileFormat = result.fileCheckResults.fileFormat
      val allMatches = fileFormat.flatMap(_.matches)
      val puidMatches = allMatches.map(_.puid.getOrElse(""))
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

      val isUnidentified = puidMatches.nonEmpty && puidMatches.forall(_.isEmpty)
      val hasMultiplePuids = puidMatches.count(_.nonEmpty) > 1
      val extensionOnlyTextFile = allMatches.headOption.exists { m =>
        m.identificationBasis.toLowerCase.contains("extension") &&
          m.extension.exists(ext => ext.equalsIgnoreCase("txt") || ext.equalsIgnoreCase("csv"))
      }

      result match {
        case r if r.consignmentType == "judgment" && judgmentDisAllowedPuid =>
          Status(result.fileId, FileScope.value, FFIDType.id, NonJudgmentFormatValue.value).pure[IO]
        case _ if isEmptyFile =>
          Status(result.fileId, FileScope.value, FFIDType.id, ZeroByteFileValue.value).pure[IO]
        case r if r.fileSize == "0" =>
          Status(result.fileId, FileScope.value, FFIDType.id, ZeroByteFileValue.value).pure[IO]
        case _ if fileFormat.isEmpty =>
          Status(result.fileId, FileScope.value, FFIDType.id, FailedValue.value).pure[IO]
        case _ if hasMultiplePuids =>
          Status(result.fileId, FileScope.value, FFIDType.id, MultipleFormatsValue.value).pure[IO]
        case _ if isUnidentified =>
          validateFileContent(result).map {
            case Some(true) =>
              Status(result.fileId, FileScope.value, FFIDType.id, SuccessValue.value)
            case _ =>
              Status(result.fileId, FileScope.value, FFIDType.id, Unidentified.value)
          }
        case _ if extensionOnlyTextFile =>
          validateFileContent(result).map {
            case Some(true) =>
              Status(result.fileId, FileScope.value, FFIDType.id, disallowedReason.getOrElse(SuccessValue.value))
            case Some(false) | None =>
              Status(result.fileId, FileScope.value, FFIDType.id, Unidentified.value)
        }
        case _ =>
          Status(result.fileId, FileScope.value, FFIDType.id, disallowedReason.getOrElse(SuccessValue.value)).pure[IO]
      }
    }
  }

  def checksumMatch(): IO[List[Status]] = {
    input.results.map(result => {
      val checksumResult = result.fileCheckResults.checksum
      val serverChecksum = checksumResult.map(_.sha256Checksum).headOption
      val clientChecksum = result.clientChecksum
      val statusValue = if(checksumResult.isEmpty) {
        FailedValue.value
      } else if (serverChecksum.contains(clientChecksum)) {
        SuccessValue.value
      } else {
        MismatchValue.value
      }
      Status(result.fileId, FileScope.value, ChecksumMatchType.id, statusValue)
    }).pure[IO]
  }

  def serverChecksum(): IO[List[Status]] = {
    for {
      fileStatuses <- statusIfEmpty(res => res.fileCheckResults.checksum.map(_.sha256Checksum).headOption, ServerChecksumType.id)
    } yield {
      val consignmentStatus = if (input.results.map(_.fileCheckResults).exists(_.checksum.isEmpty)) {
        FailedValue.value
      } else if (fileStatuses.exists(_.statusValue == FailedValue.value)) {
        CompletedWithIssuesValue.value
      } else {
        CompletedValue.value
      }
      input.results.headOption
        .map(result => Status(result.consignmentId, ConsignmentScope.value, ServerChecksumType.id, consignmentStatus, overwrite  = true)).toList ++ fileStatuses
    }
  }

  def serverAntivirus(): IO[List[Status]] = antivirus().map(av => {
    val value = if (av.exists(_.statusValue == FailedValue.value)) {
      FailedValue.value
    } else if(av.exists(_.statusValue == VirusDetectedValue.value)) {
      CompletedWithIssuesValue.value
    } else {
      CompletedValue.value
    }
    input.results.headOption.map(result => Status(result.consignmentId, ConsignmentScope.value, ServerAntivirusType.id, value, overwrite = true)).toList
  })

  def clientChecksum(): IO[List[Status]] = statusIfEmpty(res => res.clientChecksum.some, ClientChecksumType.id)

  def clientFilePath(): IO[List[Status]] = statusIfEmpty(res => res.originalPath.some, ClientFilePathType.id)

  def redactedStatus(): IO[List[Status]] = {
    input.redactedResults.redactedFiles.map(red => Status(red.redactedFileId, FileScope.value, RedactionType.id, SuccessValue.value)) ++
      input.redactedResults.errors.map(err => Status(err.fileId, FileScope.value, RedactionType.id, err.cause))
  }.pure[IO]

  def serverFFID(): IO[List[Status]] = {
    for {
      fileFFID <- ffid()
    } yield {
      val activeDisallowedReasons = allPuidInformation.disallowedPuids.filter(_.active).map(_.reason)
      val hasErrors = fileFFID.map(_.statusValue).exists(v => activeDisallowedReasons.contains(v) || v == MultipleFormatsValue.value)
      val isFailed = fileFFID.exists(_.statusValue == FailedValue.value)
      input.results.headOption.map(i => {
        val statusValue = if(isFailed) {
          FailedValue.value
        } else if (hasErrors) {
          CompletedWithIssuesValue.value
        } else {
          CompletedValue.value
        }
        Status(i.consignmentId, ConsignmentScope.value, ServerFFIDType.id, statusValue, overwrite = true)
      }).toList
    }
  }

  def serverRedaction(): IO[List[Status]] = {
    for {
      redactedResults <- redactedStatus()
    } yield {
      val statusValue = if (redactedResults.exists(_.statusValue != SuccessValue.value)) { CompletedWithIssuesValue.value } else CompletedValue.value
      input.results.headOption.map(result => Status(result.consignmentId, ConsignmentScope.value, ServerRedactionType.id, statusValue, overwrite = true)).toList
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
        if(s.statusName == FFIDType.id) {
          s.statusValue == ZeroByteFileValue.value || s.statusValue == MultipleFormatsValue.value
        } else {
          s.statusValue != SuccessValue.value
        }
      }).map(_.id).toSet
      val successfulIds = allStatuses.map(_.id).toSet.diff(failedIds)
      (failedIds.map(id => Status(id, FileScope.value, ClientChecksType.id, CompletedWithIssuesValue.value)) ++
        successfulIds.map(id => Status(id, FileScope.value, ClientChecksType.id, CompletedValue.value))).toList
    }
  }

  def consignmentClientChecks(): IO[List[Status]] = {
    fileClientChecks().map(checks => {
      val result = checks.find(_.statusValue == CompletedWithIssuesValue.value).map(_.statusValue).getOrElse(CompletedValue.value)
      input.results.headOption.map(res => {
        Status(res.consignmentId, ConsignmentScope.value, ClientChecksType.id, result, overwrite = true)
      }).toList
    })
  }

  private def statusIfEmpty(fn: File => Option[String], statusName: String): IO[List[Status]] = {
    input.results.map(res => {
      val value = fn(res)
      val statusValue = if (value.getOrElse("").equals("")) {
        FailedValue.value
      } else {
        SuccessValue.value
      }
      Status(res.fileId, FileScope.value, statusName, statusValue)
    }).pure[IO]
  }
}

object StatusProcessor {
  private lazy val s3Utils: S3Utils = S3Utils(s3Async(sys.env("S3_ENDPOINT")))

  def apply(input: Input, allPuidInformation: AllPuidInformation): StatusProcessor =
    new StatusProcessor(input, allPuidInformation, s3Utils)
}

