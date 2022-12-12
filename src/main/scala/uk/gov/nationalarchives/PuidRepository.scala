package uk.gov.nationalarchives

import cats.effect.Resource
import cats.effect.kernel.Concurrent
import cats.effect.std.Console
import cats.implicits._
import fs2.io.net.Network
import natchez.Trace.Implicits.noop
import skunk._
import skunk.codec.all.text
import skunk.implicits._
import uk.gov.nationalarchives.DatabaseConfig.DatabaseCredentials
import uk.gov.nationalarchives.PuidRepository.{AllPuidInformation, PuidInformation}

class PuidRepository[F[_]: Concurrent: Network: Console](sessionResource: Resource[F, Session[F]]) {

  private def allowedPuids(): F[List[PuidInformation]] = {
    val query: Query[Void, PuidInformation] =
      sql"""SELECT "PUID", 'NonJudgmentFormat'::text FROM "AllowedPuids"; """
        .query(text ~ text)
        .gmap[PuidInformation]
    sessionResource.use { session =>
      session.execute[PuidInformation](query)
    }
  }

  private def disAllowedPuids(): F[List[PuidInformation]] = {
    val query: Query[Void, PuidInformation] =
      sql"""SELECT "PUID", "Reason" FROM "DisallowedPuids" """
        .query(text ~ text)
        .gmap[PuidInformation]
    sessionResource.use { session =>
      session.execute[PuidInformation](query)
    }
  }

  def allPuids: F[AllPuidInformation] = for {
    allowedPuids <- allowedPuids()
    disallowedPuids <- disAllowedPuids()
  } yield AllPuidInformation(allowedPuids, disallowedPuids)

}
object PuidRepository {
  case class AllPuidInformation(allowedPuids: List[PuidInformation], disallowedPuids: List[PuidInformation])
  case class PuidInformation(puid: String, reason: String)

  def apply[F[_]: Concurrent: Network: Console](credentials: DatabaseCredentials): PuidRepository[F] = {
    val session = Session.single[F](credentials.host, credentials.port ,credentials.username, "consignmentapi", Some(credentials.password))
    new PuidRepository[F](session)
  }
}
