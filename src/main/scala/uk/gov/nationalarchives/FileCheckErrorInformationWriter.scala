package uk.gov.nationalarchives

import cats.effect.IO
import cats.implicits._
import io.circe.Printer
import io.circe.generic.auto._
import io.circe.syntax._
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import uk.gov.nationalarchives.BackendCheckUtils._
import uk.gov.nationalarchives.tdr.common.utils.objectkeycontext.ObjectCategories.FileChecks
import uk.gov.nationalarchives.tdr.common.utils.statuses.StatusScopes.FileScope
import uk.gov.nationalarchives.tdr.common.utils.statuses.StatusValues.{CompletedValue, SuccessValue}

import java.util.UUID

class FileCheckErrorInformationWriter(s3Endpoint: String, environment: String) {

  private val backendChecksUtils = BackendCheckUtils(s3Endpoint)
  private val errorBucket: String = s"tdr-transfer-errors-$environment"
  private implicit val logger: Logger[IO] = Slf4jLogger.getLogger[IO]

  def writeFileCheckErrors(input: Input, statuses: List[Status]): IO[Unit] = {
    val filesById = input.results.map(f => (f.fileId, f)).toMap
    statuses
      .filter(s => s.statusType == FileScope.value && !Set(SuccessValue.value, CompletedValue.value).contains(s.statusValue))
      .map(s => FileCheckErrorInformationWriter.StatusWithoutOverwrite(s.id, s.statusType, s.statusName, s.statusValue))
      .groupBy(_.id)
      .toList
      .parTraverse_ { case (fileId, statuses) =>
        filesById.get(fileId).fold(Logger[IO].warn(s"Missing file for lookup id $fileId; skipping")) { file =>
          val error = FileCheckErrorInformationWriter.FileCheckErrorInformation(file, statuses)
          val key = s"${file.consignmentId}/${FileChecks.id}/$fileId.error"
          IO.fromEither(backendChecksUtils.writeResultJson(key, errorBucket, error.asJson.printWith(Printer.noSpaces))).void
        }
      }
  }
}

object FileCheckErrorInformationWriter {
  case class StatusWithoutOverwrite(id: UUID, statusType: String, statusName: String, statusValue: String)
  case class FileCheckErrorInformation(file: File, statuses: List[StatusWithoutOverwrite])
}
