package uk.gov.nationalarchives

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import com.dimafeng.testcontainers.{ContainerDef, PostgreSQLContainer}
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.{anyUrl, get, ok, put, urlEqualTo}
import com.github.tomakehurst.wiremock.http.RequestMethod
import doobie.Transactor
import doobie.implicits._
import doobie.util.transactor.Transactor.Aux
import io.circe.generic.auto._
import io.circe.parser.decode
import io.circe.syntax._
import io.circe.Printer.noSpaces
import org.mockito.MockitoSugar
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.prop.TableDrivenPropertyChecks
import uk.gov.nationalarchives.BackendCheckUtils._

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import scala.jdk.CollectionConverters._
import scala.io.Source

class TestUtils extends AnyFlatSpec with TableDrivenPropertyChecks with MockitoSugar with TestContainerForAll {
  val Success = "Success"
  val Completed = "Completed"
  val Failed = "Failed"

  override val containerDef: ContainerDef = PostgreSQLContainer.Def(
    databaseName = "consignmentapi",
    username = "tdr",
    password = "password"
  )

  val wiremockS3Server = new WireMockServer(9005)

  def setupS3ForWrite(): Unit = {
    wiremockS3Server.stubFor(put(anyUrl()).willReturn(ok()))
  }

  def putJsonFile(s3Input: S3Input, inputJson: String): S3Input = {
    wiremockS3Server
      .stubFor(get(urlEqualTo(s"/${s3Input.bucket}/${s3Input.key}")).willReturn(ok(inputJson)))
    s3Input
  }

  val allowedJudgmentPuid = "fmt/000"
  val disallowedJudgmentPuid = "fmt/111"
  val allowedStandardPuid = "fmt/003"
  val activeDisallowedStandardPuid = "fmt/001"
  val inactiveDisallowedStandardPuid = "fmt/002"
  val passwordProtectedPuid = "fmt/494"

  override def afterContainersStart(containers: containerDef.Container): Unit = {
    containers match {
      case container: PostgreSQLContainer =>
        val jdbcUrl = s"jdbc:postgresql://localhost:${container.mappedPort(5432)}/consignmentapi"
        val xa: Aux[IO, Unit] = Transactor.fromDriverManager[IO](
          "org.postgresql.Driver", jdbcUrl, "tdr", "password", None
        )
        (for {
          res1 <- sql"""CREATE TABLE public."AllowedPuids" ("PUID" text not null)""".update.run.transact(xa)
          res2 <- sql"""INSERT INTO "AllowedPuids" ("PUID") VALUES ($allowedJudgmentPuid) """.update.run.transact(xa)
          res2 <- sql"""INSERT INTO "AllowedPuids" ("PUID") VALUES ('fmt/001') """.update.run.transact(xa)
          res3 <- sql"""CREATE TABLE public."DisallowedPuids" ("PUID" text not null, "Reason" text not null, "Active" boolean not null default true)""".update.run.transact(xa)
          res4 <-
            sql"""INSERT INTO "DisallowedPuids" ("PUID", "Reason", "Active") VALUES
                 ($activeDisallowedStandardPuid, 'Invalid', true),
                 ($inactiveDisallowedStandardPuid, 'Inactive', false),
                 ('', 'ZeroByteFile', true),
                 ($passwordProtectedPuid, 'PasswordProtected', true) """.update.run.transact(xa)
        } yield List(res1, res2, res3, res4)).unsafeRunSync()
    }
    super.afterContainersStart(containers)
  }

  def getInputFromS3(): Input = wiremockS3Server.getAllServeEvents.asScala.find(_.getRequest.getMethod == RequestMethod.PUT)
    .flatMap(ev => {
      val bodyString = ev.getRequest.getBodyAsString.split("\r\n")(1)
      decode[Input](bodyString).toOption
    }).get

  def getStatuses(inputReplacements: Map[String, String], container: PostgreSQLContainer): List[Status] = {
    System.setProperty("db-port", container.mappedPort(5432).toString)
    val inputString = Source.fromResource("input.json").mkString
    val input = inputReplacements.foldLeft(inputString)((ir, r) => {
      ir.replace(s"{${r._1}}", r._2)
    })
    val s3Input = putJsonFile(S3Input("testKey", "testBucket"), input).asJson.printWith(noSpaces)
    val in = new ByteArrayInputStream(s3Input.getBytes())
    val out = new ByteArrayOutputStream()
    new Lambda().run(in, out)
    getInputFromS3().statuses.statuses
  }

  def getStatus(inputReplacements: Map[String, String], container: PostgreSQLContainer, statusName: String, statusType: String): String = {
    val statuses = getStatuses(inputReplacements, container)
    statuses.filter(s => s.statusName == statusName && s.statusType == statusType).map(_.statusValue).head
  }
}
