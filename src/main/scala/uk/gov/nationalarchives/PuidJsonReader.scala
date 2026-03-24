package uk.gov.nationalarchives

import cats.effect.IO
import com.fasterxml.jackson.databind.ObjectMapper
import io.circe.generic.auto._
import io.circe.jawn.decode
import uk.gov.nationalarchives.PuidJsonReader.{AllPuidInformation, AllowedPuids, DisallowedPuids, PuidChecksumEntry}

import scala.io.Source
import scala.util.Using

class PuidJsonReader(allowedPuidsResult: Either[io.circe.Error, List[AllowedPuids]],
                     disallowedPuidsResult: Either[io.circe.Error, List[DisallowedPuids]],
                     puidChecksumsResult: Either[io.circe.Error, List[PuidChecksumEntry]]) {

  private def allowedPuids(): IO[List[AllowedPuids]] = {
    IO.fromEither(allowedPuidsResult)
  }

  private def disallowedPuids(): IO[List[DisallowedPuids]] = {
    IO.fromEither(disallowedPuidsResult)
  }

  private def puidChecksums(): IO[List[PuidChecksumEntry]] = {
    IO.fromEither(puidChecksumsResult)
  }

  def allPuids: IO[AllPuidInformation] = for {
    allowedPuids <- allowedPuids()
    disallowedPuids <- disallowedPuids()
    puidChecksums <- puidChecksums()
  } yield AllPuidInformation(allowedPuids, disallowedPuids, puidChecksums)
}

object PuidJsonReader {

  private val allowedPuidsConfiguration: String = "puids/allowed-puids.json"
  private val disallowedPuidConfiguration: String = "puids/disallowed-puids.json"
  private val puidChecksumsConfiguration: String = "puids/puid-checksums.json"

  case class AllPuidInformation(allowedPuids: List[AllowedPuids], disallowedPuids: List[DisallowedPuids], puidChecksums: List[PuidChecksumEntry])

  case class DisallowedPuids(puid: String, puidDescription: String, reason: String, active: Boolean)
  case class AllowedPuids(puid: String, puidDescription: String, consignmentType: String)
  case class PuidChecksumDetail(checksum: String, checksumDescription: String)
  case class PuidChecksumEntry(puid: String, checksums: List[PuidChecksumDetail])

  def apply(): PuidJsonReader = {
    val allowedPuidsResult = decode[List[AllowedPuids]](readJson(allowedPuidsConfiguration))
    val disallowedPuidsResult = decode[List[DisallowedPuids]](readJson(disallowedPuidConfiguration))
    val puidChecksumsResult = decode[List[PuidChecksumEntry]](readJson(puidChecksumsConfiguration))
    new PuidJsonReader(allowedPuidsResult, disallowedPuidsResult, puidChecksumsResult)
  }

  private def readJson(jsonFile: String): String = {
    val nodeSchema = Using(Source.fromResource(jsonFile))(_.mkString)
    val mapper = new ObjectMapper()
    mapper.readTree(nodeSchema.get).toPrettyString
  }
}
