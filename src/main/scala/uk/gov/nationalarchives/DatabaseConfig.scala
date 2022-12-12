package uk.gov.nationalarchives

import cats.implicits._
import cats.{Monad, MonadError}
import pureconfig._
import pureconfig.generic.auto._
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.http.apache.ApacheHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.rds.RdsUtilities
import software.amazon.awssdk.services.rds.model.GenerateAuthenticationTokenRequest
import software.amazon.awssdk.services.ssm.SsmClient
import software.amazon.awssdk.services.ssm.model.GetParameterRequest
import uk.gov.nationalarchives.DatabaseConfig.{Config, DatabaseCredentials}

import java.net.URI

class DatabaseConfig[F[_]: Monad](config: Config) {

  private def getSsmParameter(path: String): F[String] = {
    val httpClient = ApacheHttpClient.builder.build()
    val ssmClient: SsmClient = SsmClient.builder
      .endpointOverride(URI.create(config.ssmEndpoint))
      .httpClient(httpClient)
      .region(Region.EU_WEST_2)
      .build()
    val getParameterRequest = GetParameterRequest.builder.name(path).withDecryption(true).build
    ssmClient.getParameter(getParameterRequest).parameter().value().pure[F]
  }

  private def getPassword(host: String, config: Config): F[String] = {
    if(config.useIamAuth.getOrElse(false)) {
      val rdsUtilities = RdsUtilities.builder().region(Region.EU_WEST_2).build()
      val request = GenerateAuthenticationTokenRequest
        .builder()
        .credentialsProvider(DefaultCredentialsProvider.builder().build())
        .hostname(host)
        .port(config.dbPort)
        .username(config.username)
        .region(Region.EU_WEST_2)
        .build()
      rdsUtilities.generateAuthenticationToken(request).pure[F]
    } else {
      config.password.getOrElse("").pure[F]
    }

  }

  def credentials: F[DatabaseCredentials] = {
    for {
      host <- config.urlPath.map(getSsmParameter).getOrElse(config.host.pure[F])
      password <- getPassword(host, config)
    } yield DatabaseCredentials(config.username, password, host, config.dbPort)
  }


}
object DatabaseConfig {
  case class DatabaseCredentials(username: String, password: String, host: String, port: Int)

  case class Config(username: String, urlPath: Option[String], ssmEndpoint: String, dbPort: Int, useIamAuth: Option[Boolean], password: Option[String], host: String)

  def apply[F[_]]()(implicit me: MonadError[F, Throwable]): F[DatabaseConfig[F]] = for {
    config <- ConfigSource.default.load[Config] match {
      case Left(value) =>
        me.raiseError(new RuntimeException(value.toList.map(_.description).mkString))
      case Right(value) => value.pure[F]
    }
  } yield new DatabaseConfig[F](config)
}
