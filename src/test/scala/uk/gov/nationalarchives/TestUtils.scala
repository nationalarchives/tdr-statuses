package uk.gov.nationalarchives

import cats.effect.IO
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import com.dimafeng.testcontainers.{ContainerDef, PostgreSQLContainer}
import natchez.Trace.Implicits.noop
import org.mockito.MockitoSugar
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.prop.TableDrivenPropertyChecks
import skunk._
import cats.effect.unsafe.implicits.global
import skunk.codec.all.text
import skunk.implicits._
import uk.gov.nationalarchives.Lambda.{Status, StatusResult}
import io.circe.generic.auto._
import io.circe.parser.decode

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import scala.io.Source

class TestUtils extends AnyFlatSpec with TableDrivenPropertyChecks with MockitoSugar with TestContainerForAll {
  val Success = "Success"
  val Failed = "Failed"

  override val containerDef: ContainerDef = PostgreSQLContainer.Def(
    databaseName = "consignmentapi",
    username = "tdr",
    password = "password"
  )

  override def afterContainersStart(containers: containerDef.Container): Unit = {
    containers match {
      case container: PostgreSQLContainer =>
        Session.single[IO](container.host, container.mappedPort(5432), "tdr", "consignmentapi", Some("password")).use { session =>
          val allowedCreate: Command[Void] = sql"""CREATE TABLE public."AllowedPuids" ("PUID" text not null)""".command
          val allowedInsert: Command[String] = sql"""INSERT INTO "AllowedPuids" SELECT $text """.command
          val disAllowedCreate: Command[Void] = sql"""CREATE TABLE public."DisallowedPuids" ("PUID" text not null, "Reason" text not null)""".command
          val disAllowedInsert: Command[(String, String)] = sql"""INSERT INTO "DisallowedPuids" SELECT $text, $text """.command
          for {
            _ <- session.execute(allowedCreate)
            _ <- session.prepare(allowedInsert).use { pc => pc.execute("fmt/000") }
            _ <- session.execute(disAllowedCreate)
            _ <- session.prepare(disAllowedInsert).use { pc => pc.execute("fmt/001" ~ "Invalid") }
          } yield ()
        }.unsafeRunSync()

    }
    super.afterContainersStart(containers)
  }

  def getStatuses(inputReplacements: Map[String, String], container: PostgreSQLContainer): List[Status] = {
    System.setProperty("db-port", container.mappedPort(5432).toString)
    val inputString = Source.fromResource("input.json").mkString
    val input = inputReplacements.foldLeft(inputString)((ir, r) => {
      ir.replace(s"{${r._1}}", r._2)
    })
    val in = new ByteArrayInputStream(input.getBytes())
    val out = new ByteArrayOutputStream()
    new Lambda().run(in, out)
    val output = out.toByteArray.map(_.toChar).mkString
    decode[StatusResult](output).getOrElse(StatusResult(Nil)).statuses
  }

  def getStatus(inputReplacements: Map[String, String], container: PostgreSQLContainer, statusName: String): String = {
    val statuses = getStatuses(inputReplacements, container)
    statuses.filter(_.statusName == statusName).map(_.statusValue).head
  }

}
