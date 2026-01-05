package uk.gov.nationalarchives

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Printer
import io.circe.generic.auto._
import io.circe.parser.decode
import io.circe.syntax._

import java.io.{InputStream, OutputStream}
import scala.io.Source
import uk.gov.nationalarchives.BackendCheckUtils._

class Lambda {

  private val backendChecksUtils = BackendCheckUtils(sys.env("S3_ENDPOINT"))

  private def statusProcessor(input: Input): IO[StatusProcessor[IO]] = for {
    allPuids <- PuidJsonReader().allPuids
    processor <- StatusProcessor[IO](input, allPuids)
  } yield processor

  private def statusChecks(processor: StatusProcessor[IO]): IO[List[Status]] = for {
    ffid <- processor.ffid()
    av <- processor.antivirus()
    checksumMatch <- processor.checksumMatch()
    serverChecksum <- processor.serverChecksum()
    clientChecksum <- processor.clientChecksum()
    clientFilePath <- processor.clientFilePath()
    redactedStatus <- processor.redactedStatus()
    serverFFID <- processor.serverFFID()
    serverAV <- processor.serverAntivirus()
    serverRedaction <- processor.serverRedaction()
    fileClientChecks <- processor.fileClientChecks()
    consignmentClientChecks <- processor.consignmentClientChecks()
  } yield ffid ::: av ::: checksumMatch ::: serverChecksum ::: clientChecksum :::
    clientFilePath ::: redactedStatus ::: serverFFID ::: fileClientChecks :::
    consignmentClientChecks ::: serverAV ::: serverRedaction

  def run(inputStream: InputStream, outputStream: OutputStream): Unit = {
    val inputString = Source.fromInputStream(inputStream).mkString
    val result = for {
      s3Input <- IO.fromEither(decode[S3Input](inputString))
      input <- IO.fromEither(backendChecksUtils.getResultJson(s3Input.key, s3Input.bucket))
      processor <- statusProcessor(input)
      statuses <- statusChecks(processor)
      resultString = Input(input.results, input.redactedResults, StatusResult(statuses)).asJson.printWith(Printer.noSpaces)
      _ <- IO.fromEither(backendChecksUtils.writeResultJson(s3Input.key, s3Input.bucket, resultString))
    } yield s3Input

    outputStream.write(result.unsafeRunSync().asJson.printWith(Printer.noSpaces).getBytes())
  }
}
