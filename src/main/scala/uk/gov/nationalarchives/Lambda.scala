package uk.gov.nationalarchives

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.typesafe.config.ConfigFactory
import io.circe.Printer
import io.circe.generic.auto._
import io.circe.parser.decode
import io.circe.syntax._
import software.amazon.awssdk.services.sns.SnsClient
import uk.gov.nationalarchives.BackendCheckUtils._
import uk.gov.nationalarchives.services.{FileCheckStatusEvaluator, GraphQlApiService, NotificationService}

import java.io.{InputStream, OutputStream}
import java.net.URI
import scala.io.Source

class Lambda(fileCheckStatusEvaluator: => FileCheckStatusEvaluator = Lambda.defaultEvaluator) {

  private val backendChecksUtils = BackendCheckUtils(sys.env("S3_ENDPOINT"))


  // ...existing code...
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
      _ <- input.results.headOption match {
        case Some(result) => fileCheckStatusEvaluator.processAndNotify(result.consignmentId, statuses).void
        case None         => IO.unit
      }
    } yield s3Input

    outputStream.write(result.unsafeRunSync().asJson.printWith(Printer.noSpaces).getBytes())
  }
}

object Lambda {
  private lazy val config = ConfigFactory.load()

  private lazy val snsClient: SnsClient = SnsClient.builder()
    .endpointOverride(URI.create(config.getString("sns.endpoint")))
    .build()

  private lazy val notificationService: NotificationService =
    NotificationService(snsClient, config.getString("sns.topicArn"))

  lazy val defaultEvaluator: FileCheckStatusEvaluator =
    FileCheckStatusEvaluator(GraphQlApiService.service, notificationService)
}

