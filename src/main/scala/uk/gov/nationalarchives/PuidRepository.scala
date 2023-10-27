package uk.gov.nationalarchives

import cats.effect.kernel.Async
import cats.implicits._
import doobie.Transactor
import doobie.implicits._
import doobie.util.transactor.Transactor.Aux
import uk.gov.nationalarchives.DatabaseConfig.DatabaseCredentials
import uk.gov.nationalarchives.PuidRepository.{AllPuidInformation, AllowedPuids, DisallowedPuids}

class PuidRepository[F[_]: Async](xa: Aux[F, Unit]) {

  private def allowedPuids(): F[List[AllowedPuids]] = {
    val query = sql"""SELECT "PUID" FROM "AllowedPuids"; """.query[AllowedPuids].to[List]
    query.transact(xa)
  }

  private def disallowedPuids(): F[List[DisallowedPuids]] = {
    val query = sql"""SELECT "PUID", "Reason", "Active" FROM "DisallowedPuids"; """.query[DisallowedPuids].to[List]
    query.transact(xa)
  }

  def allPuids: F[AllPuidInformation] = for {
    allowedPuids <- allowedPuids()
    disallowedPuids <- disallowedPuids()
  } yield AllPuidInformation(allowedPuids, disallowedPuids)

}

object PuidRepository {
  case class AllPuidInformation(allowedPuids: List[AllowedPuids], disallowedPuids: List[DisallowedPuids])

  case class DisallowedPuids(puid: String, reason: String, active: Boolean)
  case class AllowedPuids(puid: String)

  def apply[F[_] : Async](credentials: DatabaseCredentials): PuidRepository[F] = {
    val suffix = if(credentials.useIamAuth) {
      val certificatePath = getClass.getResource("/rds-eu-west-2-bundle.pem").getPath
      s"?ssl=true&sslrootcert=$certificatePath&sslmode=verify-full"
    } else {
      ""
    }
    val jdbcUrl = s"jdbc:postgresql://${credentials.host}:${credentials.port}/consignmentapi$suffix"
    val xa: Aux[F, Unit] = Transactor.fromDriverManager[F](
      "org.postgresql.Driver", jdbcUrl, credentials.username, credentials.password, None
    )
    new PuidRepository[F](xa)
  }
}
