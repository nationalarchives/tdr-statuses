package uk.gov.nationalarchives

import cats.effect.kernel.Async
import cats.implicits._
import doobie.Transactor
import doobie.implicits._
import doobie.util.transactor.Transactor.Aux
import uk.gov.nationalarchives.DatabaseConfig.DatabaseCredentials
import uk.gov.nationalarchives.PuidRepository.{AllPuidInformation, PuidInformation}

class PuidRepository[F[_]: Async](xa: Aux[F, Unit]) {

  private def allowedPuids(): F[List[PuidInformation]] = {
    val query = sql"""SELECT "PUID", 'NonJudgmentFormat'::text FROM "AllowedPuids"; """.query[PuidInformation].to[List]
    query.transact(xa)
  }

  private def disAllowedPuids(): F[List[PuidInformation]] = {
    val query = sql"""SELECT "PUID", "Reason" FROM "DisallowedPuids"; """.query[PuidInformation].to[List]
    query.transact(xa)
  }

  def allPuids: F[AllPuidInformation] = for {
    allowedPuids <- allowedPuids()
    disallowedPuids <- disAllowedPuids()
  } yield AllPuidInformation(allowedPuids, disallowedPuids)

}

object PuidRepository {
  case class AllPuidInformation(allowedPuids: List[PuidInformation], disallowedPuids: List[PuidInformation])

  case class PuidInformation(puid: String, reason: String)

  def apply[F[_] : Async](credentials: DatabaseCredentials): PuidRepository[F] = {
    val suffix = if(credentials.useIamAuth) {
      val certificatePath = getClass.getResource("/rds-ca-2019-root.pem").getPath
      s"?ssl=true&sslrootcert=$certificatePath&sslmode=verify-full"
    } else {
      ""
    }
    val jdbcUrl = s"jdbc:postgresql://${credentials.host}:${credentials.port}/consignmentapi$suffix"
    val xa: Aux[F, Unit] = Transactor.fromDriverManager[F](
      "org.postgresql.Driver", jdbcUrl, credentials.username, credentials.password
    )
    new PuidRepository[F](xa)
  }
}
