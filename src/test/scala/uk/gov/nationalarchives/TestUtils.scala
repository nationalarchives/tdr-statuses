package uk.gov.nationalarchives

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import com.dimafeng.testcontainers.{ContainerDef, PostgreSQLContainer}
import doobie.Transactor
import doobie.implicits._
import doobie.util.transactor.Transactor.Aux
import io.circe.generic.auto._
import io.circe.parser.decode
import org.mockito.MockitoSugar
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.prop.TableDrivenPropertyChecks
import uk.gov.nationalarchives.Lambda.{Status, StatusResult}

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
        val jdbcUrl = s"jdbc:postgresql://localhost:${container.mappedPort(5432)}/consignmentapi"
        val xa: Aux[IO, Unit] = Transactor.fromDriverManager[IO](
          "org.postgresql.Driver", jdbcUrl, "tdr", "password"
        )
        val a = (for {
          res1 <- sql"""CREATE TABLE public."AllowedPuids" ("PUID" text not null)""".update.run.transact(xa)
          res2 <- sql"""INSERT INTO "AllowedPuids" ("PUID") VALUES ('fmt/000') """.update.run.transact(xa)
          res3 <- sql"""CREATE TABLE public."DisallowedPuids" ("PUID" text not null, "Reason" text not null)""".update.run.transact(xa)
          res4 <- sql"""INSERT INTO "DisallowedPuids" ("PUID", "Reason") VALUES ('fmt/001', 'Invalid') """.update.run.transact(xa)
        } yield List(res1, res2, res3, res4)).unsafeRunSync()
        a





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
