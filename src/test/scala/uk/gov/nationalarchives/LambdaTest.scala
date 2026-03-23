package uk.gov.nationalarchives

import cats.effect.IO
import io.circe.Printer
import io.circe.Printer.noSpaces
import io.circe.generic.auto._
import io.circe.parser.decode
import io.circe.syntax._
import org.mockito.ArgumentMatchers.{any, argThat}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers._
import org.scalatest.prop.{TableFor2, TableFor3, TableFor4, TableFor5}
import uk.gov.nationalarchives.BackendCheckUtils._
import uk.gov.nationalarchives.services.FileCheckStatusEvaluator

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.util.UUID
import scala.io.Source

class LambdaTest extends TestUtils with BeforeAndAfterAll {

  val zeroByteChecksum = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
  val bomFileChecksum = "f01a374e9c81e3db89b3a42940c4d6a5447684986a1296e42bf13f196eed6295"

  val ffidResults: TableFor5[String, List[String], String, String, String] = Table(
    ("consignmentType", "puid", "serverChecksum", "fileSize", "result"),
    ("judgment", List(s"$disallowedJudgmentPuid"), "validChecksum", "1", "NonJudgmentFormat"),
    ("judgment", List(s"$allowedJudgmentPuid"), "validChecksum", "1", "Success"),
    ("standard", List(s"$inactiveDisallowedStandardPuid"), "validChecksum", "1", "Success"),
    ("standard", List(s"$activeDisallowedStandardPuid"), "validChecksum", "1", "Zip"),
    ("standard", List(s"$allowedStandardPuid"), zeroByteChecksum, "1", "ZeroByteFile"),
    ("standard", List(s"$allowedJudgmentPuid", s"$activeDisallowedStandardPuid"), zeroByteChecksum, "1", "ZeroByteFile"),
    ("standard", List(s"$allowedJudgmentPuid", s"$inactiveDisallowedStandardPuid"), zeroByteChecksum, "1", "ZeroByteFile"),
    ("standard", List(s"$activeDisallowedStandardPuid", s"$activeDisallowedStandardPuid"), zeroByteChecksum, "1", "ZeroByteFile"),
    ("standard", List(s"$passwordProtectedPuid", s"$inactiveDisallowedStandardPuid"), zeroByteChecksum, "1", "ZeroByteFile"),
    ("standard", List(s"$allowedStandardPuid"), bomFileChecksum, "1", "ZeroByteFile"),
    ("standard", List(s"$allowedStandardPuid"), "validChecksum", "0", "ZeroByteFile"),
    ("standard", List(s"$activeDisallowedStandardPuid"), "validChecksum", "0", "ZeroByteFile")
  )
  val avResults: TableFor2[String, String] = Table(
    ("avResult", "expectedStatus"),
    ("virusFound", "VirusDetected"),
    ("", Success)
  )

  "run" should "return success statuses if incoming json is valid" in {
    val replacementsMap = Map("Antivirus" -> "", "ClientChecksum" -> "abc", "ServerChecksum" -> "abc", "FileSize" -> "1")
    val statuses: List[Status] = getStatuses(replacementsMap)

    statuses.size should equal(12)

    def filterStatus(name: String): List[String] = statuses.filter(_.statusName == name).map(_.statusValue)

    filterStatus("FFID").head should equal(Success)
    filterStatus("Antivirus").head should equal(Success)
    filterStatus("ChecksumMatch").head should equal(Success)
    filterStatus("ServerChecksum").head should equal(Completed)
    filterStatus("ServerChecksum").last should equal(Success)
    filterStatus("ClientChecksum").head should equal(Success)
    filterStatus("ClientFilePath").head should equal(Success)
    filterStatus("ServerRedaction").head should equal(Completed)
  }
  val checksumMatchResults: TableFor3[String, String, String] = Table(
    ("serverChecksum", "clientChecksum", "expectedStatus"),
    ("match", "match", Success),
    ("match", "mismatch", "Mismatch"),
    ("mismatch", "match", "Mismatch")
  )

  forAll(ffidResults)((consignmentType, puids, serverChecksum, fileSize, expectedStatus) => {
    "run" should s"return a $expectedStatus if the $consignmentType puid is ${puids.mkString(",")} and server checksum is $serverChecksum and fileSize is $fileSize" in {

      val consignmentId = UUID.randomUUID()
      val matches = puids.map(puid => {
        FFIDMetadataInputMatches(Option("extension"), "identificationBasis", Option(puid), Some(false), Some("format-name"))
      })
      val fileId = UUID.randomUUID()
      val checksumResults = List(ChecksumResult(serverChecksum, UUID.randomUUID()))
      val fileChecks = FileCheckResults(Nil, checksumResults, FFID(fileId, "software", "softwareVersion", "binarySignature", "containerSignature", "method", matches) :: Nil)
      val files = File(consignmentId, fileId, UUID.randomUUID(), consignmentType, fileSize, "checksum", "originalPath", Some("source-bucket"), Some("object/key"), fileChecks) :: Nil
      val inputString = Input(files, RedactedResults(Nil, Nil), StatusResult(Nil)).asJson.printWith(Printer.noSpaces)
      val s3Input = putJsonFile(S3Input("testKey", "testBucket"), inputString).asJson.printWith(noSpaces)
      val input = new ByteArrayInputStream(s3Input.getBytes())
      val output = new ByteArrayOutputStream()
      new Lambda(FileCheckStatusEvaluator.noOp).run(input, output)

      val result = getInputFromS3().statuses
      val ffidStatus = result.statuses.find(_.statusName == "FFID").get
      ffidStatus.statusValue should equal(expectedStatus)
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

  forAll(avResults)((avResult, expectedStatus) => {
    "run" should s"return $expectedStatus if the AV result is $avResult" in {
      val inputReplacements = Map("Antivirus" -> avResult)
      val status = getStatus(inputReplacements, "Antivirus", "File")
      status should equal(expectedStatus)
    }
  })
  val serverFFIDResults: TableFor2[List[String], String] = Table(
    ("puids", "expectedResult"),
    (List(s"$allowedJudgmentPuid", s"$inactiveDisallowedStandardPuid"), "Completed"),
    (List(s"$allowedJudgmentPuid"), "Completed"),
    (List(s"$allowedJudgmentPuid", s"$activeDisallowedStandardPuid"), "CompletedWithIssues"),
    (List(s"$activeDisallowedStandardPuid"), "CompletedWithIssues")
  )

  forAll(checksumMatchResults)((serverChecksum, clientChecksum, expectedStatus) => {
    "run" should s"return $expectedStatus for server checksum $serverChecksum and client checksum $clientChecksum" in {
      val inputReplacements = Map("ServerChecksum" -> serverChecksum, "ClientChecksum" -> clientChecksum)
      val status = getStatus(inputReplacements, "ChecksumMatch", "File")
      status should equal(expectedStatus)
    }
  })
  val serverAvResults: TableFor2[List[String], String] = Table(
    ("avResults", "expectedResult"),
    (List("virus", ""), "CompletedWithIssues"),
    (List("anotherVirus"), "CompletedWithIssues"),
    (List("virus", "virus"), "CompletedWithIssues"),
    (List("", ""), "Completed"),
  )

  forAll(emptyCheckResults)((statusName, value, expectedStatus) => {
    "run" should s"return $expectedStatus for $statusName with value $value" in {
      val inputReplacements = Map(statusName -> value)
      val status = getStatus(inputReplacements, statusName, "File")
      status should equal(expectedStatus)
    }
  })

  "run" should "return the correct redacted statuses for redacted files" in {
    val input = decode[Input](Source.fromResource("input.json").mkString).toOption.get
    val filePair = RedactedFilePairs(UUID.randomUUID(), "original", UUID.randomUUID(), "redacted")
    val errors = RedactedErrors(UUID.randomUUID(), "TestFailureReason")
    val redactedResults = input.redactedResults
      .copy(redactedFiles = filePair :: Nil, errors = errors :: Nil)
    val inputString = input.copy(redactedResults = redactedResults).asJson.printWith(Printer.noSpaces)
    val s3Input = putJsonFile(S3Input("testKey", "testBucket"), inputString).asJson.printWith(noSpaces)
    val outputStream = new ByteArrayOutputStream()

    new Lambda(FileCheckStatusEvaluator.noOp).run(new ByteArrayInputStream(s3Input.getBytes()), outputStream)

    val result = getInputFromS3().statuses
    val redactionStatuses = result.statuses.filter(_.statusName == "Redaction")
    redactionStatuses.size should equal(2)
    redactionStatuses.count(_.statusValue == Success) should equal(1)
    redactionStatuses.count(_.statusValue == "TestFailureReason") should equal(1)
  }
  val redactedFilePairs = RedactedFilePairs(UUID.randomUUID(), "originalFilePath", UUID.randomUUID(), "redactedFilePath")

  forAll(serverFFIDResults)((puids, expectedResult) => {
    "run" should s"return the expected consignment status $expectedResult for puids ${puids.mkString(" ")}" in {
      
      val consignmentId = UUID.randomUUID()
      val files = puids.map(puid => {
        val fileId = UUID.randomUUID()
        val matches = FFIDMetadataInputMatches(Option("extension"),"identificationBasis" ,Option(puid), Some(false), Some("format-name")) :: Nil
        val fileChecks = FileCheckResults(Nil, Nil, FFID(fileId, "software", "softwareVersion", "binarySignature", "containerSignature", "method", matches) :: Nil)
        File(consignmentId, fileId, UUID.randomUUID(), "standard", "1", "checksum", "originalPath", Some("source-bucket"), Some("object/key"), fileChecks)
      })
      val inputString = Input(files, RedactedResults(Nil, Nil), StatusResult(Nil)).asJson.printWith(Printer.noSpaces)
      val s3Input = putJsonFile(S3Input("testKey", "testBucket"), inputString).asJson.printWith(noSpaces)
      val input = new ByteArrayInputStream(s3Input.getBytes())
      val output = new ByteArrayOutputStream()
      new Lambda(FileCheckStatusEvaluator.noOp).run(input, output)

      val result = getInputFromS3().statuses
      val serverFFIDStatuses = result.statuses.filter(_.statusName == "ServerFFID")
      serverFFIDStatuses.size should equal(1)
      serverFFIDStatuses.head.id should equal(consignmentId)
      serverFFIDStatuses.head.statusValue should equal(expectedResult)
    }
  })
  val redactionResults: TableFor3[String, RedactedResults, String] = Table(
    ("description", "redactedResults", "expectedResult"),
    ("no redactions", RedactedResults(Nil, Nil), "Completed"),
    ("redactions with no errors", RedactedResults(List(redactedFilePairs), Nil), "Completed"),
    ("redactions with errors", RedactedResults(List(redactedFilePairs), List(RedactedErrors(UUID.randomUUID(), "error cause"))), "CompletedWithIssues"),
  )

  forAll(serverAvResults)((avResults, expectedResult) => {
    "run" should s"return the expected consignment status $expectedResult for av results ${avResults.mkString(" ")}" in {
      val consignmentId = UUID.randomUUID()
      val files = avResults.map(avResult => {
        val antivirus: Antivirus = Antivirus(UUID.randomUUID(), "software", "softwareVersion", "databaseVersion", avResult, 1)
        val fileChecks = FileCheckResults(List(antivirus), Nil, Nil)
        File(consignmentId, UUID.randomUUID(), UUID.randomUUID(), "standard", "1", "checksum", "originalPath", Some("source-bucket"), Some("object/key"), fileChecks)
      })
      val inputString = Input(files, RedactedResults(Nil, Nil), StatusResult(Nil)).asJson.printWith(Printer.noSpaces)
      val s3Input = putJsonFile(S3Input("testKey", "testBucket"), inputString).asJson.printWith(noSpaces)
      val input = new ByteArrayInputStream(s3Input.getBytes())
      val output = new ByteArrayOutputStream()
      new Lambda(FileCheckStatusEvaluator.noOp).run(input, output)

      val result = getInputFromS3().statuses
      val serverAVStatuses = result.statuses.filter(_.statusName == "ServerAntivirus")
      serverAVStatuses.size should equal(1)
      serverAVStatuses.head.id should equal(consignmentId)
      serverAVStatuses.head.statusValue should equal(expectedResult)
    }
  })
  val fileClientChecksResults: TableFor5[String, String, String, String, String] = Table(
    ("clientChecksum", "clientFilePath", "ffid", "serverChecksum", "expectedStatus"),
    ("", "path", s"$allowedJudgmentPuid", "validChecksum", "CompletedWithIssues"),
    ("checksum", "", s"$allowedJudgmentPuid", "validChecksum", "CompletedWithIssues"),
    ("", "", s"$activeDisallowedStandardPuid", "validChecksum", "CompletedWithIssues"),
    ("checksum", "path", s"$activeDisallowedStandardPuid", "validChecksum", "Completed"),
    ("checksum", "path", "", zeroByteChecksum, "CompletedWithIssues"),
    ("checksum", "path", s"$allowedJudgmentPuid", "validChecksum", "Completed")
  )
  val consignmentClientChecksResults: TableFor2[List[String], String] = Table(
    ("fileClientChecks", "expectedResult"),
    (List("Completed", "CompletedWithIssues"), "CompletedWithIssues"),
    (List("CompletedWithIssues", "CompletedWithIssues"), "CompletedWithIssues"),
    (List("Completed", "Completed"), "Completed"),
    (List("Completed"), "Completed"),
  )

  forAll(redactionResults)((description, redactedResults, expectedResult) => {
    "run" should s"return the expected consignment status $expectedResult for $description" in {
      
      val consignmentId = UUID.randomUUID()
      val files = List(File(consignmentId, UUID.randomUUID(), UUID.randomUUID(), "standard", "1", "checksum",
        "originalPath", Some("source-bucket"), Some("object/key"), FileCheckResults(Nil, Nil, Nil)))

      val inputString = Input(files, redactedResults, StatusResult(Nil)).asJson.printWith(Printer.noSpaces)
      val s3Input = putJsonFile(S3Input("testKey", "testBucket"), inputString).asJson.printWith(noSpaces)
      val input = new ByteArrayInputStream(s3Input.getBytes())
      val output = new ByteArrayOutputStream()
      new Lambda(FileCheckStatusEvaluator.noOp).run(input, output)

      val result = getInputFromS3().statuses
      val serverRedaction = result.statuses.filter(_.statusName == "ServerRedaction")
      serverRedaction.size should equal(1)
      serverRedaction.head.id should equal(consignmentId)
      serverRedaction.head.statusValue should equal(expectedResult)
    }
  })
  val missingInfo: TableFor3[Int, Int, Option[String]] = Table(
    ("missingInfoCount", "providedInfoCount", "expectedConsignmentStatus"),
    (1, 0, Option("Failed")),
    (1, 1, Option("Failed")),
    (0, 1, Option("Completed")),
    (0, 0, None),
  )

  forAll(fileClientChecksResults)((clientChecksum, clientFilePath, ffid, serverChecksum, expectedStatus) => {
    "run" should s"return $expectedStatus for $clientChecksum, $clientFilePath, $ffid" in {
      val inputReplacements = Map(
        "ClientChecksum" -> clientChecksum,
        "ClientFilePath" -> clientFilePath,
        "FFID" -> ffid,
        "ConsignmentType" -> "standard",
        "ServerChecksum" -> serverChecksum
      )
      val status = getStatus(inputReplacements, "ClientChecks", "Consignment")
      status should equal(expectedStatus)
    }
  })

  override def beforeAll(): Unit = {
    setupS3ForWrite()
    wiremockS3Server.start()
  }

  forAll(consignmentClientChecksResults)((fileClientChecks, expectedResult) => {
    "run" should s"return the expected consignment status $expectedResult for client checks ${fileClientChecks.mkString(" ")}" in {
      val consignmentId = UUID.randomUUID()
      val files: List[File] = fileClientChecks map {
        case "Completed" => File(consignmentId, UUID.randomUUID(), UUID.randomUUID(), "standard", "1", "checksum", "originalPath", Some("source-bucket"), Some("object/key"), FileCheckResults(Nil, Nil, Nil))
        case "CompletedWithIssues" => File(consignmentId, UUID.randomUUID(), UUID.randomUUID(), "standard", "1", "", "originalPath", Some("source-bucket"), Some("object/key"), FileCheckResults(Nil, Nil, Nil))
      }
      val inputString = Input(files, RedactedResults(Nil, Nil), StatusResult(Nil)).asJson.printWith(Printer.noSpaces)
      val s3Input = putJsonFile(S3Input("testKey", "testBucket"), inputString).asJson.printWith(noSpaces)
      val input = new ByteArrayInputStream(s3Input.getBytes())
      val output = new ByteArrayOutputStream()
      new Lambda(FileCheckStatusEvaluator.noOp).run(input, output)

      val result = getInputFromS3().statuses
      val clientChecksStatuses = result.statuses.filter(s => s.statusName == "ClientChecks" && s.statusType == "Consignment")
      clientChecksStatuses.size should equal(1)
      clientChecksStatuses.head.id should equal(consignmentId)
      clientChecksStatuses.head.statusValue should equal(expectedResult)
    }
  })

  override def afterAll(): Unit = {
    wiremockS3Server.stop()
  }

  forAll(missingInfo)((missingInfoCount, providedInfoCount, expectedConsignmentStatus) => {
    val antivirus = Antivirus(UUID.randomUUID(), "software", "softwareVersion", "databaseVersion", "", 1) :: Nil
    val fileId = UUID.randomUUID()
    val ffid = FFID(fileId, "software", "softwareVersion", "binarySignature", "containerSignature", "method", Nil) :: Nil
    val checksum = ChecksumResult("checksum", UUID.randomUUID()) :: Nil
    val createFile = File(UUID.randomUUID(), fileId, UUID.randomUUID(), "standard", "1", "checksum", "originalPath", Some("source-bucket"), Some("object/key"), _)
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
      new Lambda(FileCheckStatusEvaluator.noOp).run(input, output)

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
      new Lambda(FileCheckStatusEvaluator.noOp).run(input, output)

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
      new Lambda(FileCheckStatusEvaluator.noOp).run(input, output)

      val result = getInputFromS3().statuses
      result.statuses.count(s => s.statusName == "FFID" && s.statusValue == "Failed") should equal(missingInfoCount)
      result.statuses.find(_.statusName == "ServerFFID").map(_.statusValue) should equal(expectedConsignmentStatus)
    }
  })

  "backend checks statuses" should "return Failed if the input  is empty" in {
    val fileCheckResults = FileCheckResults(Nil, Nil, Nil)
    val files = File(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "standard", "1", "checksum", "path", Some("source-bucket"), Some("object/key"), fileCheckResults) :: Nil

    val inputString = Input(files, RedactedResults(Nil, Nil), StatusResult(Nil)).asJson.printWith(Printer.noSpaces)
    val s3Input = putJsonFile(S3Input("testKey", "testBucket"), inputString).asJson.printWith(Printer.noSpaces)

    val input = new ByteArrayInputStream(s3Input.getBytes())
    val output = new ByteArrayOutputStream()
    new Lambda(FileCheckStatusEvaluator.noOp).run(input, output)

    val result = getInputFromS3().statuses
    result.statuses.find(_.statusName == "FFID").get.statusValue should equal("Failed")
    result.statuses.find(_.statusName == "Antivirus").get.statusValue should equal("Failed")
    result.statuses.find(_.statusName == "ChecksumMatch").get.statusValue should equal("Failed")
  }

  "run" should "invoke the evaluator's processAndNotify with the correct consignment ID and statuses" in {
    val consignmentId = UUID.randomUUID()
    val userId: UUID = UUID.randomUUID()
    val matches = FFIDMetadataInputMatches(Option("extension"), "identificationBasis", Option(allowedJudgmentPuid), Some(false), Some("format-name")) :: Nil
    val fileId = UUID.randomUUID()
    val fileChecks = FileCheckResults(Nil, Nil, FFID(fileId, "software", "softwareVersion", "binarySignature", "containerSignature", "method", matches) :: Nil)

    val file = uk.gov.nationalarchives.BackendCheckUtils.File(
      consignmentId,
      fileId,
      userId,
      "standard",
      "FileSize",
      "Checksum": String,
      "originalPath": String,
      Some("3SourceBucket"): Option[String],
      Some("s3SourceBucketKey"),
      fileChecks
    )


    val files = file :: Nil
    val inputString = Input(files, RedactedResults(Nil, Nil), StatusResult(Nil)).asJson.printWith(Printer.noSpaces)
    val s3Input = putJsonFile(S3Input("testKey", "testBucket"), inputString).asJson.printWith(noSpaces)

    val mockEvaluator = mock[FileCheckStatusEvaluator]
    when(mockEvaluator.processAndNotify(any[File], any[List[Status]])).thenReturn(IO.pure(None))

    val input = new ByteArrayInputStream(s3Input.getBytes())
    val output = new ByteArrayOutputStream()
    new Lambda(mockEvaluator).run(input, output)



    verify(mockEvaluator).processAndNotify(
      argThat[File]((res: File) => res == file),
      argThat[List[Status]]((statuses: List[Status]) => statuses.nonEmpty)
    )
  }
}
