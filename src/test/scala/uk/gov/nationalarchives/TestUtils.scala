package uk.gov.nationalarchives

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.http.RequestMethod
import io.circe.Printer.noSpaces
import io.circe.generic.auto._
import io.circe.parser.decode
import io.circe.syntax._
import org.mockito.MockitoSugar
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.prop.TableDrivenPropertyChecks
import uk.gov.nationalarchives.BackendCheckUtils._

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import scala.io.Source
import scala.jdk.CollectionConverters._

class TestUtils extends AnyFlatSpec with TableDrivenPropertyChecks with MockitoSugar {
  val Success = "Success"
  val Completed = "Completed"
  val Failed = "Failed"

  val wiremockS3Server = new WireMockServer(9005)

  def setupS3ForWrite(): Unit = {
    wiremockS3Server.stubFor(put(anyUrl()).willReturn(ok()))
  }

  def putJsonFile(s3Input: S3Input, inputJson: String): S3Input = {
    wiremockS3Server
      .stubFor(get(urlEqualTo(s"/${s3Input.bucket}/${s3Input.key}")).willReturn(ok(inputJson)))
    s3Input
  }

  val allowedJudgmentPuid = "fmt/412"
  val disallowedJudgmentPuid = "x-fmt/45"
  val allowedStandardPuid = "fmt/189"
  val activeDisallowedStandardPuid = "fmt/600"
  val inactiveDisallowedStandardPuid = "fmt/002"
  val passwordProtectedPuid = "fmt/494"

  def getInputFromS3(): Input = wiremockS3Server.getAllServeEvents.asScala.find(_.getRequest.getMethod == RequestMethod.PUT)
    .flatMap(ev => {
      val bodyString = ev.getRequest.getBodyAsString.split("\r\n")(1)
      decode[Input](bodyString).toOption
    }).get

  def getStatuses(inputReplacements: Map[String, String]): List[Status] = {
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

  def getStatus(inputReplacements: Map[String, String], statusName: String, statusType: String): String = {
    val statuses = getStatuses(inputReplacements)
    statuses.filter(s => s.statusName == statusName && s.statusType == statusType).map(_.statusValue).head
  }
}
