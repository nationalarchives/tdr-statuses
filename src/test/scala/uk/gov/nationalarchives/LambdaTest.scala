package uk.gov.nationalarchives

import com.dimafeng.testcontainers.PostgreSQLContainer
import io.circe.Printer
import io.circe.Printer.noSpaces
import io.circe.generic.auto._
import io.circe.parser.decode
import io.circe.syntax._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers._
import org.scalatest.prop.{TableFor2, TableFor3, TableFor4, TableFor5}
import uk.gov.nationalarchives.BackendCheckUtils._

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.util.UUID
import scala.io.Source

class LambdaTest extends TestUtils with BeforeAndAfterAll {

  override def beforeAll(): Unit = {
    setupS3ForWrite()
    wiremockS3Server.start()
  }

  override def afterAll(): Unit = {
    wiremockS3Server.stop()
  }

  override def afterContainersStart(containers: containerDef.Container): Unit = super.afterContainersStart(containers)

  "run" should "return success statuses if incoming json is valid" in withContainers { case container: PostgreSQLContainer =>
    val replacementsMap = Map("Antivirus" -> "", "ClientChecksum" -> "abc", "ServerChecksum" -> "abc", "FileSize" -> "1")
    val statuses: List[Status] = getStatuses(replacementsMap, container)

    statuses.size should equal(11)

    def filterStatus(name: String): List[String] = statuses.filter(_.statusName == name).map(_.statusValue)

    filterStatus("FFID").head should equal(Success)
    filterStatus("Antivirus").head should equal(Success)
    filterStatus("ChecksumMatch").head should equal(Success)
    filterStatus("ServerChecksum").head should equal(Completed)
    filterStatus("ServerChecksum").last should equal(Success)
    filterStatus("ClientChecksum").head should equal(Success)
    filterStatus("ClientFilePath").head should equal(Success)
  }

  val ffidResults: TableFor4[String, List[String], String, String] = Table(
    ("consignmentType", "puid", "fileSize", "result"),
    ("judgment", List("fmt/111"), "1", "NonJudgmentFormat"),
    ("judgment", List("fmt/000"), "1", "Success"),
    ("standard", List("fmt/002"), "1", "Success"),
    ("standard", List("fmt/001"), "1", "Invalid"),
    ("standard", List("fmt/003"), "0", "Success"),
    ("standard", List("fmt/000", "fmt/001"), "0", "Invalid"),
    ("standard", List("fmt/000", "fmt/002"), "0", "Success"),
    ("standard", List("fmt/001", "fmt/001"), "0", "Invalid")
  )

  forAll(ffidResults)((consignmentType, puids, fileSize, expectedStatus) => {
    "run" should s"return a $expectedStatus if the $consignmentType puid is ${puids.mkString(",")} and file size is $fileSize" in withContainers { case container: PostgreSQLContainer =>
      System.setProperty("db-port", container.mappedPort(5432).toString)
      val consignmentId = UUID.randomUUID()
      val matches = puids.map(puid => {
        FFIDMetadataInputMatches(Option("extension"), "identificationBasis", Option(puid))
      })
      val fileChecks = FileCheckResults(Nil, Nil, FFID(UUID.randomUUID(), "software", "softwareVersion", "binarySignature", "containerSignature", "method", matches) :: Nil)
      val files = File(consignmentId, UUID.randomUUID(), UUID.randomUUID(), consignmentType, fileSize, "checksum", "originalPath", fileChecks) :: Nil
      val inputString = Input(files, RedactedResults(Nil, Nil), StatusResult(Nil)).asJson.printWith(Printer.noSpaces)
      val s3Input = putJsonFile(S3Input("testKey", "testBucket"), inputString).asJson.printWith(noSpaces)
      val input = new ByteArrayInputStream(s3Input.getBytes())
      val output = new ByteArrayOutputStream()
      new Lambda().run(input, output)

      val result = getInputFromS3().statuses
      val ffidStatus = result.statuses.find(_.statusName == "FFID").get
      ffidStatus.statusValue should equal(expectedStatus)
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
      val status = getStatus(inputReplacements, container, "Antivirus", "File")
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
      val status = getStatus(inputReplacements, container, "ChecksumMatch", "File")
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
      val status = getStatus(inputReplacements, container, statusName, "File")
      status should equal(expectedStatus)
    }
  })

  "run" should "return the correct redacted statuses for redacted files" in withContainers { case container: PostgreSQLContainer =>
    System.setProperty("db-port", container.mappedPort(5432).toString)
    val input = decode[Input](Source.fromResource("input.json").mkString).toOption.get
    val filePair = RedactedFilePairs(UUID.randomUUID(), "original", UUID.randomUUID(), "redacted")
    val errors = RedactedErrors(UUID.randomUUID(), "TestFailureReason")
    val redactedResults = input.redactedResults
      .copy(redactedFiles = filePair :: Nil, errors = errors :: Nil)
    val inputString = input.copy(redactedResults = redactedResults).asJson.printWith(Printer.noSpaces)
    val s3Input = putJsonFile(S3Input("testKey", "testBucket"), inputString).asJson.printWith(noSpaces)
    val outputStream = new ByteArrayOutputStream()

    new Lambda().run(new ByteArrayInputStream(s3Input.getBytes()), outputStream)

    val result = getInputFromS3().statuses
    val redactionStatuses = result.statuses.filter(_.statusName == "Redaction")
    redactionStatuses.size should equal(2)
    redactionStatuses.count(_.statusValue == Success) should equal(1)
    redactionStatuses.count(_.statusValue == "TestFailureReason") should equal(1)
  }

  val serverFFIDResults: TableFor2[List[String], String] = Table(
    ("puids", "expectedResult"),
    (List("fmt/000", "fmt/002"), "Completed"),
    (List("fmt/000"), "Completed"),
    (List("fmt/000", "fmt/001"), "CompletedWithIssues"),
    (List("fmt/001"), "CompletedWithIssues")
  )

  forAll(serverFFIDResults)((puids, expectedResult) => {
    "run" should s"return the expected consignment status $expectedResult for puids ${puids.mkString(" ")}" in withContainers { case container: PostgreSQLContainer =>
      System.setProperty("db-port", container.mappedPort(5432).toString)
      val consignmentId = UUID.randomUUID()
      val files = puids.map(puid => {
        val matches = FFIDMetadataInputMatches(Option("extension"),"identificationBasis" ,Option(puid)) :: Nil
        val fileChecks = FileCheckResults(Nil, Nil, FFID(UUID.randomUUID(), "software", "softwareVersion", "binarySignature", "containerSignature", "method", matches) :: Nil)
        File(consignmentId, UUID.randomUUID(), UUID.randomUUID(), "standard", "1", "checksum", "originalPath", fileChecks)
      })
      val inputString = Input(files, RedactedResults(Nil, Nil), StatusResult(Nil)).asJson.printWith(Printer.noSpaces)
      val s3Input = putJsonFile(S3Input("testKey", "testBucket"), inputString).asJson.printWith(noSpaces)
      val input = new ByteArrayInputStream(s3Input.getBytes())
      val output = new ByteArrayOutputStream()
      new Lambda().run(input, output)

      val result = getInputFromS3().statuses
      val serverFFIDStatuses = result.statuses.filter(_.statusName == "ServerFFID")
      serverFFIDStatuses.size should equal(1)
      serverFFIDStatuses.head.id should equal(consignmentId)
      serverFFIDStatuses.head.statusValue should equal(expectedResult)
    }
  })

  val serverAvResults: TableFor2[List[String], String] = Table(
    ("avResults", "expectedResult"),
    (List("virus", ""), "CompletedWithIssues"),
    (List("anotherVirus"), "CompletedWithIssues"),
    (List("virus", "virus"), "CompletedWithIssues"),
    (List("", ""), "Completed"),
  )

  forAll(serverAvResults)((avResults, expectedResult) => {
    "run" should s"return the expected consignment status $expectedResult for av results ${avResults.mkString(" ")}" in withContainers { case container: PostgreSQLContainer =>
      System.setProperty("db-port", container.mappedPort(5432).toString)
      val consignmentId = UUID.randomUUID()
      val files = avResults.map(avResult => {
        val antivirus: Antivirus = Antivirus(UUID.randomUUID(), "software", "softwareVersion", "databaseVersion", avResult, 1)
        val fileChecks = FileCheckResults(List(antivirus), Nil, Nil)
        File(consignmentId, UUID.randomUUID(), UUID.randomUUID(), "standard", "1", "checksum", "originalPath", fileChecks)
      })
      val inputString = Input(files, RedactedResults(Nil, Nil), StatusResult(Nil)).asJson.printWith(Printer.noSpaces)
      val s3Input = putJsonFile(S3Input("testKey", "testBucket"), inputString).asJson.printWith(noSpaces)
      val input = new ByteArrayInputStream(s3Input.getBytes())
      val output = new ByteArrayOutputStream()
      new Lambda().run(input, output)

      val result = getInputFromS3().statuses
      val serverAVStatuses = result.statuses.filter(_.statusName == "ServerAntivirus")
      serverAVStatuses.size should equal(1)
      serverAVStatuses.head.id should equal(consignmentId)
      serverAVStatuses.head.statusValue should equal(expectedResult)
    }
  })

  val fileClientChecksResults: TableFor5[String, String, String, String, String] = Table(
    ("clientChecksum", "clientFilePath", "ffid", "fileSize", "expectedStatus"),
    ("", "path", "fmt/000", "1", "CompletedWithIssues"),
    ("checksum", "", "fmt/000", "1", "CompletedWithIssues"),
    ("", "", "fmt/001", "1", "CompletedWithIssues"),
    ("checksum", "path", "fmt/001", "1", "Completed"),
    ("checksum", "path", "", "0", "CompletedWithIssues"),
    ("checksum", "path", "fmt/000", "1", "Completed")
  )

  forAll(fileClientChecksResults)((clientChecksum, clientFilePath, ffid, fileSize, expectedStatus) => {
    "run" should s"return $expectedStatus for $clientChecksum, $clientFilePath, $ffid" in withContainers { case container: PostgreSQLContainer =>
      System.setProperty("db-port", container.mappedPort(5432).toString)
      val inputReplacements = Map(
        "ClientChecksum" -> clientChecksum,
        "ClientFilePath" -> clientFilePath,
        "FFID" -> ffid,
        "ConsignmentType" -> "standard",
        "FileSize" -> fileSize
      )
      val status = getStatus(inputReplacements, container, "ClientChecks", "Consignment")
      status should equal(expectedStatus)
    }
  })

  val consignmentClientChecksResults: TableFor2[List[String], String] = Table(
    ("fileClientChecks", "expectedResult"),
    (List("Completed", "CompletedWithIssues"), "CompletedWithIssues"),
    (List("CompletedWithIssues", "CompletedWithIssues"), "CompletedWithIssues"),
    (List("Completed", "Completed"), "Completed"),
    (List("Completed"), "Completed"),
  )

  forAll(consignmentClientChecksResults)((fileClientChecks, expectedResult) => {
    "run" should s"return the expected consignment status $expectedResult for client checks ${fileClientChecks.mkString(" ")}" in withContainers { case container: PostgreSQLContainer =>
      System.setProperty("db-port", container.mappedPort(5432).toString)
      val consignmentId = UUID.randomUUID()
      val files: List[File] = fileClientChecks map {
        case "Completed" => File(consignmentId, UUID.randomUUID(), UUID.randomUUID(), "standard", "1", "checksum", "originalPath", FileCheckResults(Nil, Nil, Nil))
        case "CompletedWithIssues" => File(consignmentId, UUID.randomUUID(), UUID.randomUUID(), "standard", "0", "", "originalPath", FileCheckResults(Nil, Nil, Nil))
      }
      val inputString = Input(files, RedactedResults(Nil, Nil), StatusResult(Nil)).asJson.printWith(Printer.noSpaces)
      val s3Input = putJsonFile(S3Input("testKey", "testBucket"), inputString).asJson.printWith(noSpaces)
      val input = new ByteArrayInputStream(s3Input.getBytes())
      val output = new ByteArrayOutputStream()
      new Lambda().run(input, output)

      val result = getInputFromS3().statuses
      val clientChecksStatuses = result.statuses.filter(s => s.statusName == "ClientChecks" && s.statusType == "Consignment")
      clientChecksStatuses.size should equal(1)
      clientChecksStatuses.head.id should equal(consignmentId)
      clientChecksStatuses.head.statusValue should equal(expectedResult)
    }
  })

  val missingInfo: TableFor3[Int, Int, Option[String]] = Table(
    ("missingInfoCount", "providedInfoCount", "expectedConsignmentStatus"),
    (1, 0, Option("Failed")),
    (1, 1, Option("Failed")),
    (0, 1, Option("Completed")),
    (0, 0, None),
  )

  forAll(missingInfo)((missingInfoCount, providedInfoCount, expectedConsignmentStatus) => {
    val antivirus = Antivirus(UUID.randomUUID(), "software", "softwareVersion", "databaseVersion", "", 1) :: Nil
    val ffid = FFID(UUID.randomUUID(), "software", "softwareVersion", "binarySignature", "containerSignature", "method", Nil) :: Nil
    val checksum = ChecksumResult("checksum", UUID.randomUUID()) :: Nil
    val createFile = File(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "standard", "1", "checksum", "originalPath", _)
    val providedInfoFiles = List.fill(providedInfoCount)("")
      .map(_ => createFile(FileCheckResults(antivirus, checksum, ffid)))

    "antivirus statuses" should s"return $expectedConsignmentStatus if $missingInfoCount files have missing information and $providedInfoCount files have provided information" in {
      val missingInfoFiles = List.fill(missingInfoCount)("")
        .map(_ => createFile(FileCheckResults(Nil, checksum, ffid)))
      val files = missingInfoFiles ++ providedInfoFiles

      val inputString = Input(files, RedactedResults(Nil, Nil), StatusResult(Nil)).asJson.printWith(Printer.noSpaces)
      val s3Input = putJsonFile(S3Input("testKey", "testBucket"), inputString).asJson.printWith(noSpaces)
      val input = new ByteArrayInputStream(s3Input.getBytes())
      val output = new ByteArrayOutputStream()
      new Lambda().run(input, output)

      val result = getInputFromS3().statuses
      result.statuses.count(s => s.statusName == "Antivirus" && s.statusValue == "Failed") should equal(missingInfoCount)
      result.statuses.find(_.statusName == "ServerAntivirus").map(_.statusValue) should equal(expectedConsignmentStatus)
    }

    "checksum statuses" should s"return $expectedConsignmentStatus if $missingInfoCount files have missing information and $providedInfoCount files have provided information" in {
      val missingInfoFiles = List.fill(missingInfoCount)("")
        .map(_ => createFile(FileCheckResults(antivirus, Nil, ffid)))
      val files = missingInfoFiles ++ providedInfoFiles
      val inputString = Input(files, RedactedResults(Nil, Nil), StatusResult(Nil)).asJson.printWith(Printer.noSpaces)
      val s3Input = putJsonFile(S3Input("testKey", "testBucket"), inputString).asJson.printWith(noSpaces)
      val input = new ByteArrayInputStream(s3Input.getBytes())
      val output = new ByteArrayOutputStream()
      new Lambda().run(input, output)

      val result = getInputFromS3().statuses
      result.statuses.count(s => s.statusName == "ServerChecksum" && s.statusType == "File" && s.statusValue == "Failed") should equal(missingInfoCount)
      result.statuses.find(s => s.statusName == "ServerChecksum" && s.statusType == "Consignment").map(_.statusValue) should equal(expectedConsignmentStatus)
    }

    "file format statuses" should s"return $expectedConsignmentStatus if $missingInfoCount files have missing information and $providedInfoCount files have provided information" in {
      val missingInfoFiles = List.fill(missingInfoCount)("")
        .map(_ => createFile(FileCheckResults(antivirus, checksum, Nil)))
      val files = missingInfoFiles ++ providedInfoFiles
      val inputString = Input(files, RedactedResults(Nil, Nil), StatusResult(Nil)).asJson.printWith(Printer.noSpaces)
      val s3Input = putJsonFile(S3Input("testKey", "testBucket"), inputString).asJson.printWith(noSpaces)
      val input = new ByteArrayInputStream(s3Input.getBytes())
      val output = new ByteArrayOutputStream()
      new Lambda().run(input, output)

      val result = getInputFromS3().statuses
      result.statuses.count(s => s.statusName == "FFID" && s.statusValue == "Failed") should equal(missingInfoCount)
      result.statuses.find(_.statusName == "ServerFFID").map(_.statusValue) should equal(expectedConsignmentStatus)
    }
  })
}
