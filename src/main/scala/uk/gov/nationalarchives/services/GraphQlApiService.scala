package uk.gov.nationalarchives.services

import cats.effect.IO
import com.nimbusds.oauth2.sdk.token.BearerAccessToken
import com.typesafe.config.{Config, ConfigFactory}
import graphql.codegen.GetConsignment.{getConsignment => gc}
import graphql.codegen.GetConsignmentType.{getConsignmentType => gct}
import sttp.client3.{HttpURLConnectionBackend, Identity, SttpBackend}
import uk.gov.nationalarchives.tdr.GraphQLClient
import uk.gov.nationalarchives.tdr.keycloak.{KeycloakUtils, TdrKeycloakDeployment}

import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global

final case class ConsignmentNotFound(consignmentId: UUID)
  extends Exception(s"Consignment not found: $consignmentId")

final case class ConsignmentDetails(
  consignmentId: UUID,
  consignmentType: Option[String],
  consignmentReference: String,
  transferringBody: Option[String],
  userId: UUID
)

class GraphQlApiService(
  consignmentClient: GraphQLClient[gc.Data, gc.Variables],
  consignmentTypeClient: GraphQLClient[gct.Data, gct.Variables],
  keycloakUtils: KeycloakUtils,
  clientId: String,
  clientSecret: String
)(implicit backend: SttpBackend[Identity, Any], tdrKeycloakDeployment: TdrKeycloakDeployment) {

  def getConsignmentDetails(consignmentId: UUID): IO[ConsignmentDetails] = {
    for {
      token       <- IO.fromFuture(IO(keycloakUtils.serviceAccountToken(clientId, clientSecret)))
      consignment <- getConsignment(token, consignmentId)
      consType    <- getConsignmentType(token, consignmentId)
    } yield ConsignmentDetails(
      consignmentId = consignmentId,
      consignmentType = consType.consignmentType,
      consignmentReference = consignment.consignmentReference,
      transferringBody = consignment.transferringBodyName,
      userId = consignment.userid
    )
  }

  private def getConsignment(token: BearerAccessToken, consignmentId: UUID): IO[gc.GetConsignment] = {
    val variables = gc.Variables(consignmentId)
    for {
      response    <- IO.fromFuture(IO(consignmentClient.getResult(token, gc.document, Some(variables))))
      consignment <- IO.fromEither(
        response.data
          .flatMap(_.getConsignment)
          .toRight(ConsignmentNotFound(consignmentId))
      )
    } yield consignment
  }

  private def getConsignmentType(token: BearerAccessToken, consignmentId: UUID): IO[gct.GetConsignment] = {
    val variables = gct.Variables(consignmentId)
    for {
      response    <- IO.fromFuture(IO(consignmentTypeClient.getResult(token, gct.document, Some(variables))))
      consignment <- IO.fromEither(
        response.data
          .flatMap(_.getConsignment)
          .toRight(ConsignmentNotFound(consignmentId))
      )
    } yield consignment
  }
}

object GraphQlApiService {

  private lazy val config: Config = ConfigFactory.load()

  private lazy val configBackend: SttpBackend[Identity, Any] = HttpURLConnectionBackend()
  private lazy val configDeployment: TdrKeycloakDeployment =
    TdrKeycloakDeployment(
      config.getString("auth.url"),
      config.getString("auth.realm"),
      3600
    )

  private lazy val keycloakUtils: KeycloakUtils = KeycloakUtils()

  lazy val service: GraphQlApiService = {
    implicit val backend: SttpBackend[Identity, Any] = configBackend
    implicit val deployment: TdrKeycloakDeployment = configDeployment
    val apiUrl = config.getString("api.url")
    new GraphQlApiService(
      new GraphQLClient[gc.Data, gc.Variables](apiUrl),
      new GraphQLClient[gct.Data, gct.Variables](apiUrl),
      keycloakUtils,
      config.getString("auth.clientId"),
      config.getString("auth.clientSecret")
    )
  }

  def apply(
    consignmentClient: GraphQLClient[gc.Data, gc.Variables],
    consignmentTypeClient: GraphQLClient[gct.Data, gct.Variables],
    keycloakUtils: KeycloakUtils,
    clientId: String = "test",
    clientSecret: String = "test"
  )(implicit
    backend: SttpBackend[Identity, Any],
    tdrKeycloakDeployment: TdrKeycloakDeployment
  ): GraphQlApiService =
    new GraphQlApiService(consignmentClient, consignmentTypeClient, keycloakUtils, clientId, clientSecret)
}
