package uk.gov.nationalarchives

import cats.effect.IO
import cats.implicits._
import io.circe.Printer
import io.circe.generic.auto._
import io.circe.syntax._
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import uk.gov.nationalarchives.BackendCheckUtils._
import uk.gov.nationalarchives.tdr.common.utils.statuses.StatusScopes.FileScope
import uk.gov.nationalarchives.tdr.common.utils.statuses.StatusValues.SuccessValue

import java.util.UUID

class FileCheckErrorWriter(s3Endpoint: String, environment: String) {

  private val backendChecksUtils = BackendCheckUtils(s3Endpoint)
  private val errorBucket: String = s"tdr-transfer-errors-$environment"
  private implicit val logger: Logger[IO] = Slf4jLogger.getLogger[IO]

  def writeFileCheckErrors(input: Input, statuses: List[Status]): IO[Unit] = {
    val statusErrorsByFileId = statuses
      .filter(s => s.statusType == FileScope.value && s.statusValue != SuccessValue.value)
      .map(s => FileCheckErrorWriter.StatusWithoutOverwrite(s.id, s.statusType, s.statusName, s.statusValue))
      .groupBy(_.id)
    val redactedErrorsByFileId = input.redactedResults.errors.groupBy(_.fileId)
    val filesById = input.results.map(f => (f.fileId, f)).toMap

    (statusErrorsByFileId.keys ++ redactedErrorsByFileId.keys).toList.parTraverse_ { fileId =>
      filesById.get(fileId).fold(Logger[IO].warn(s"Missing file for lookup id $fileId; skipping")) { file =>
        val error = FileCheckErrorWriter.FileCheckError(
          filePath = file.originalPath,
          fileId = file.fileId,
          statuses = statusErrorsByFileId.getOrElse(fileId, List.empty[FileCheckErrorWriter.StatusWithoutOverwrite]),
          redactedErrors = redactedErrorsByFileId.getOrElse(fileId, List.empty[RedactedErrors])
        )
        val key = s"${file.consignmentId}/filechecks/$fileId.error"
        IO.fromEither(backendChecksUtils.writeResultJson(key, errorBucket, error.asJson.printWith(Printer.noSpaces))).void
      }
    }
  }
}

object FileCheckErrorWriter {
  case class StatusWithoutOverwrite(id: UUID, statusType: String, statusName: String, statusValue: String)

  case class FileCheckError(
    filePath: String,
    fileId: UUID,
    statuses: List[StatusWithoutOverwrite],
    redactedErrors: List[RedactedErrors]
  )
}
