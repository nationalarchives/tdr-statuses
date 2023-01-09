package uk.gov.nationalarchives

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Printer
import io.circe.generic.auto._
import io.circe.parser.decode
import io.circe.syntax._
import uk.gov.nationalarchives.Lambda.{Input, StatusResult}

import java.io.{InputStream, OutputStream}
import java.util.UUID
import scala.io.Source

class Lambda {

  private def statusProcessor(inputString: String): IO[StatusProcessor[IO]] = for {
    input <- IO.fromEither(decode[Input](inputString))
    databaseConfig <- DatabaseConfig[IO]()
    credentials <- databaseConfig.credentials
    allPuids <- PuidRepository[IO](credentials).allPuids
    processor <- StatusProcessor[IO](input, allPuids)
  } yield processor

  private def statusChecks(processor: StatusProcessor[IO]): IO[List[Lambda.Status]] = for {
    ffid <- processor.ffid()
    av <- processor.antivirus()
    checksumMatch <- processor.checksumMatch()
    serverChecksum <- processor.serverChecksum()
    clientChecksum <- processor.clientChecksum()
    clientFilePath <- processor.clientFilePath()
    redactedStatus <- processor.redactedStatus()
    serverFFID <- processor.serverFFID()
  } yield ffid ::: av ::: checksumMatch ::: serverChecksum ::: clientChecksum ::: clientFilePath ::: redactedStatus ::: serverFFID

  def run(inputStream: InputStream, outputStream: OutputStream): Unit = {
    val inputString = Source.fromInputStream(inputStream).mkString
    val result = for {
      processor <- statusProcessor(inputString)
      statuses <- statusChecks(processor)
    } yield StatusResult(statuses)

    outputStream.write(result.unsafeRunSync().asJson.printWith(Printer.noSpaces).getBytes())
  }
}

object Lambda {
  case class StatusResult(statuses: List[Status])

  case class Status(id: UUID, statusType: String, statusName: String, statusValue: String, overwrite: Boolean = false)

  case class ChecksumResult(sha256Checksum: String)

  case class Matches(puid: Option[String])

  case class FFID(matches: List[Matches])

  case class Antivirus(result: String)

  case class FileCheckResults(antivirus: List[Antivirus], checksum: List[ChecksumResult], fileFormat: List[FFID])

  case class File(
                  consignmentId: UUID,
                  fileId: UUID,
                  consignmentType: String,
                  fileSize: String,
                  clientChecksum: String,
                  originalPath: String,
                  fileCheckResults: FileCheckResults
                 )

  case class RedactedResult(redactedFiles: List[RedactedFiles], errors: List[RedactedErrors])

  case class RedactedErrors(fileId: UUID, cause: String)

  case class RedactedFiles(redactedFileId: UUID)

  case class Input(results: List[File], redactedResults: RedactedResult)
}
