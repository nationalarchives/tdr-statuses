package uk.gov.nationalarchives

import cats.effect.IO
import com.fasterxml.jackson.databind.ObjectMapper
import io.circe.generic.auto._
import io.circe.jawn.decode
import uk.gov.nationalarchives.PuidJsonReader.{AllPuidInformation, AllowedPuids, DisallowedPuids}

import scala.io.Source
import scala.util.Using

class PuidJsonReader(allowedPuidsResult: Either[io.circe.Error, List[AllowedPuids]],
                     disallowedPuidsResult: Either[io.circe.Error, List[DisallowedPuids]]) {

  private def allowedPuids(): IO[List[AllowedPuids]] = {
    IO.fromEither(allowedPuidsResult)
  }

  private def disallowedPuids(): IO[List[DisallowedPuids]] = {
    IO.fromEither(disallowedPuidsResult)
  }

  def allPuids: IO[AllPuidInformation] = for {
    allowedPuids <- allowedPuids()
    disallowedPuids <- disallowedPuids()
  } yield AllPuidInformation(allowedPuids, disallowedPuids)

}

object PuidJsonReader {

  private val ALLOWED_PUIDs: String = "puids/allowed-puids.json"
  private val DISALLOWED_PUIDs: String = "puids/disallowed-puids.json"

  case class AllPuidInformation(allowedPuids: List[AllowedPuids], disallowedPuids: List[DisallowedPuids])

  case class DisallowedPuids(puid: String, puidDescription: String, reason: String, active: Boolean)
  case class AllowedPuids(puid: String, puidDescription: String, consignmentType: String)

  def apply(): PuidJsonReader = {
    val allowedPuidsResult = decode[List[AllowedPuids]](readJson(ALLOWED_PUIDs))
    val disallowedPuidsResult = decode[List[DisallowedPuids]](readJson(DISALLOWED_PUIDs))
    new PuidJsonReader(allowedPuidsResult, disallowedPuidsResult)
  }

  private def readJson(jsonFile: String): String = {
    val nodeSchema = Using(Source.fromResource(jsonFile))(_.mkString)
    val mapper = new ObjectMapper()
    mapper.readTree(nodeSchema.get).toPrettyString
  }
}
