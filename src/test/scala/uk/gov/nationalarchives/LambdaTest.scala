package uk.gov.nationalarchives

import com.dimafeng.testcontainers.PostgreSQLContainer
import io.circe.Printer
import io.circe.generic.auto._
import io.circe.parser.decode
import io.circe.syntax._
import org.scalatest.matchers.should.Matchers._
import org.scalatest.prop.{TableFor2, TableFor3, TableFor4}
import uk.gov.nationalarchives.Lambda._

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.util.UUID
import scala.io.Source

class LambdaTest extends TestUtils {

  override def afterContainersStart(containers: containerDef.Container): Unit = super.afterContainersStart(containers)

  "run" should "return success statuses if incoming json is valid" in withContainers { case container: PostgreSQLContainer =>
    val replacementsMap = Map("Antivirus" -> "", "ClientChecksum" -> "abc", "ServerChecksum" -> "abc", "FileSize" -> "1")
    val statuses: List[Status] = getStatuses(replacementsMap, container)

    statuses.size should equal(6)

    def filterStatus(name: String): List[String] = statuses.filter(_.statusName == name).map(_.statusValue)

    filterStatus("FFID").head should equal(Success)
    filterStatus("Antivirus").head should equal(Success)
    filterStatus("ChecksumMatch").head should equal(Success)
    filterStatus("ServerChecksum").head should equal(Success)
    filterStatus("ClientChecksum").head should equal(Success)
    filterStatus("ClientFilePath").head should equal(Success)
  }

  val ffidResults: TableFor4[String, String, String, String] = Table(
    ("consignmentType", "puid", "fileSize", "result"),
    ("judgment", "fmt/111", "1", "NonJudgmentFormat"),
    ("judgment", "fmt/000", "1", "Success"),
    ("standard", "fmt/002", "1", "Success"),
    ("standard", "fmt/001", "1", "Invalid"),
    ("standard", "fmt/002", "0", "ZeroByteFile")
  )

  forAll(ffidResults)((consignmentType, puid, fileSize, expectedStatus) => {
    "run" should s"return a $expectedStatus if the $consignmentType puid is $puid and file size is $fileSize" in withContainers { case container: PostgreSQLContainer =>
      val inputReplacements = Map("ConsignmentType" -> consignmentType, "FFID" -> puid, "FileSize" -> fileSize)
      val status = getStatus(inputReplacements, container, "FFID")
      status should equal(expectedStatus)
    }
  })

  val avResults: TableFor2[String, String] = Table(
    ("avResult", "expectedStatus"),
    ("virusFound", "VirusDetected"),
    ("", Success)
  )

  forAll(avResults)((avResult, expectedStatus) => {
    "run" should s"return $expectedStatus if the AV result is $avResult" in withContainers { case container: PostgreSQLContainer =>
      val inputReplacements = Map("Antivirus" -> avResult)
      val status = getStatus(inputReplacements, container, "Antivirus")
      status should equal(expectedStatus)
    }
  })

  val checksumMatchResults: TableFor3[String, String, String] = Table(
    ("serverChecksum", "clientChecksum", "expectedStatus"),
    ("match", "match", Success),
    ("match", "mismatch", "Mismatch"),
    ("mismatch", "match", "Mismatch")
  )

  forAll(checksumMatchResults)((serverChecksum, clientChecksum, expectedStatus) => {
    "run" should s"return $expectedStatus for server checksum $serverChecksum and client checksum $clientChecksum" in withContainers { case container: PostgreSQLContainer =>
      val inputReplacements = Map("ServerChecksum" -> serverChecksum, "ClientChecksum" -> clientChecksum)
      val status = getStatus(inputReplacements, container, "ChecksumMatch")
      status should equal(expectedStatus)
    }
  })

  val emptyCheckResults: TableFor3[String, String, String] = Table(
    ("statusName", "value", "expectedStatus"),
    ("ServerChecksum", "", Failed),
    ("ServerChecksum", "checksum", Success),
    ("ClientChecksum", "", Failed),
    ("ClientFilePath", "", Failed),
    ("ClientFilePath", "filePath", Success),
  )

  forAll(emptyCheckResults)((statusName, value, expectedStatus) => {
    "run" should s"return $expectedStatus for $statusName with value $value" in withContainers { case container: PostgreSQLContainer =>
      val inputReplacements = Map(statusName -> value)
      val status = getStatus(inputReplacements, container, statusName)
      status should equal(expectedStatus)
    }
  })

  "run" should "return the correct redacted statuses for redacted files" in {
    val input = decode[Input](Source.fromResource("input.json").mkString).toOption.get
    val filePair = RedactedFiles(UUID.randomUUID())
    val errors = RedactedErrors(UUID.randomUUID(), "TestFailureReason")
    val redactedResults = input.redactedResults
      .copy(redactedFiles = filePair :: Nil, errors = errors :: Nil)
    val inputString = input.copy(redactedResults = redactedResults).asJson.printWith(Printer.noSpaces)
    val outputStream = new ByteArrayOutputStream()

    new Lambda().run(new ByteArrayInputStream(inputString.getBytes()), outputStream)

    val result = decode[StatusResult](outputStream.toByteArray.map(_.toChar).mkString).getOrElse(StatusResult(Nil))
    val redactionStatuses = result.statuses.filter(_.statusName == "Redaction")
    redactionStatuses.size should equal(2)
    redactionStatuses.count(_.statusValue == Success) should equal(1)
    redactionStatuses.count(_.statusValue == "TestFailureReason") should equal(1)
  }
}
