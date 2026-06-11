package uk.gov.nationalarchives

import cats.effect.unsafe.implicits.global
import io.circe.generic.auto._
import io.circe.parser.decode
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers._
import uk.gov.nationalarchives.BackendCheckUtils._

import java.util.UUID
import scala.jdk.CollectionConverters._

class FileCheckErrorWriterSpec extends TestUtils with BeforeAndAfterAll {

  private val environment = "test"
  private val writer = new FileCheckErrorWriter(sys.env("S3_ENDPOINT"), environment)

  override def beforeAll(): Unit = {
    wiremockS3Server.start()
    setupS3ForWrite()
  }

  override def afterAll(): Unit = {
    wiremockS3Server.stop()
  }

  "writeFileCheckErrors" should "write a file-level error payload to the transfer errors bucket" in {
    wiremockS3Server.resetRequests()

    val consignmentId = UUID.randomUUID()
    val fileId = UUID.randomUUID()
    val userId = UUID.randomUUID()
    val fileChecks = FileCheckResults(Nil, Nil, Nil)
    val file = File(
      consignmentId,
      fileId,
      userId,
      "standard",
      "123",
      "checksum",
      "folder/file.txt",
      Some("source-bucket"),
      Some("object/key"),
      None,
      None,
      None,
      None,
      fileChecks
    )

    val input = Input(
      results = List(file),
      redactedResults = RedactedResults(Nil, List(RedactedErrors(fileId, "RedactionFailed"))),
      statuses = StatusResult(Nil)
    )

    val statuses = List(Status(fileId, "File", "FFID", "Failed"))

    writer.writeFileCheckErrors(input, statuses).unsafeRunSync()

    val putEvents = wiremockS3Server.getAllServeEvents.asScala.filter(_.getRequest.getMethod.getName == "PUT").toList
    putEvents.size should equal(1)

    val request = putEvents.head.getRequest
    request.getHeader("Host") should include(s"tdr-transfer-errors-$environment")
    request.getUrl should include(s"/$consignmentId/filechecks/$fileId.error")

    val payload = request.getBodyAsString.split("\r\n")(1)
    val decoded = decode[FileCheckErrorWriter.FileCheckError](payload).toOption.get

    decoded.fileId should equal(fileId)
    decoded.filePath should equal("folder/file.txt")
    decoded.statuses.map(_.statusName) should contain("FFID")
    decoded.redactedErrors.map(_.cause) should contain("RedactionFailed")
  }

  "writeFileCheckErrors" should "skip missing file ids instead of writing an error object for them" in {
    wiremockS3Server.resetRequests()

    val consignmentId = UUID.randomUUID()
    val presentFileId = UUID.randomUUID()
    val missingFileId = UUID.randomUUID()
    val userId = UUID.randomUUID()
    val fileChecks = FileCheckResults(Nil, Nil, Nil)
    val file = File(
      consignmentId,
      presentFileId,
      userId,
      "standard",
      "123",
      "checksum",
      "folder/present-file.txt",
      Some("source-bucket"),
      Some("object/key"),
      None,
      None,
      None,
      None,
      fileChecks
    )

    val input = Input(
      results = List(file),
      redactedResults = RedactedResults(Nil, List(RedactedErrors(missingFileId, "RedactionFailed"))),
      statuses = StatusResult(Nil)
    )

    val statuses = List(
      Status(presentFileId, "File", "FFID", "Failed"),
      Status(missingFileId, "File", "FFID", "Failed")
    )

    writer.writeFileCheckErrors(input, statuses).unsafeRunSync()

    val putEvents = wiremockS3Server.getAllServeEvents.asScala.filter(_.getRequest.getMethod.getName == "PUT").toList
    putEvents.size should equal(1)
    putEvents.head.getRequest.getUrl should include(s"/$consignmentId/filechecks/$presentFileId.error")
    putEvents.head.getRequest.getUrl should not include missingFileId.toString
  }
}
