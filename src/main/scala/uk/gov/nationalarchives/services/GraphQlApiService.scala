package uk.gov.nationalarchives.services

import cats.effect.IO
import com.nimbusds.oauth2.sdk.token.BearerAccessToken
import com.typesafe.config.{Config, ConfigFactory}
import graphql.codegen.GetConsignmentSummary
import graphql.codegen.GetConsignmentSummary.{getConsignmentSummary => gcs}
import software.amazon.awssdk.http.apache.ApacheHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ssm.SsmClient
import software.amazon.awssdk.services.ssm.model.GetParameterRequest
import sttp.client3.{HttpURLConnectionBackend, Identity, SttpBackend}
import uk.gov.nationalarchives.BackendCheckUtils.File
import uk.gov.nationalarchives.tdr.GraphQLClient
import uk.gov.nationalarchives.tdr.keycloak.{KeycloakUtils, TdrKeycloakDeployment}

import java.net.URI
import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global

final case class ConsignmentSummaryNotFound(consignmentId: UUID)
  extends Exception(s"Consignment summary not found: $consignmentId")

final case class ConsignmentDetails(
  consignmentId: UUID,
  consignmentType: String,
  consignmentReference: String,
  transferringBody: Option[String],
  userId: UUID
)

class GraphQlApiService(
                         consignmentSummaryClient: GraphQLClient[gcs.Data, gcs.Variables],
                         keycloakUtils: KeycloakUtils,
                         clientId: String,
                         clientSecretProvider: () => String

)(implicit backend: SttpBackend[Identity, Any], tdrKeycloakDeployment: TdrKeycloakDeployment) {

  def getConsignmentDetails(result: File) : IO[ConsignmentDetails] = {
    for {
      clientSec   <- IO(clientSecretProvider())
      token       <- IO.fromFuture(IO(keycloakUtils.serviceAccountToken(clientId, clientSec)))
      consignmentSummary <- getConsignmentSummary(token, result.consignmentId)
     } yield ConsignmentDetails(
      consignmentId = result.consignmentId,
      consignmentType = result.consignmentType,
      consignmentReference = consignmentSummary.consignmentReference,
      transferringBody = consignmentSummary.transferringBodyName,
      userId = result.userId
    )
  }

  private def getConsignmentSummary(token: BearerAccessToken, consignmentId: UUID): IO[GetConsignmentSummary.getConsignmentSummary.GetConsignment] = {
    val variables = gcs.Variables(consignmentId)
    for {
      response    <- IO.fromFuture(IO(consignmentSummaryClient.getResult(token, gcs.document, Some(variables))))
      consignmentSummary <- IO.fromEither(
        response.data
          .flatMap(_.getConsignment)
          .toRight(ConsignmentSummaryNotFound(consignmentId))
      )
    } yield consignmentSummary
  }
}

object GraphQlApiService {

  private lazy val config: Config = ConfigFactory.load()

  private lazy val configBackend: SttpBackend[Identity, Any] = HttpURLConnectionBackend()
  private lazy val tdrKeycloakDeployment: TdrKeycloakDeployment =
    TdrKeycloakDeployment(
      config.getString("auth.url"),
      config.getString("auth.realm"),
      3600
    )

  private lazy val keycloakUtils: KeycloakUtils = KeycloakUtils()

  private def getClientSecret(secretPath: String, endpoint: String): String = {
    val httpClient = ApacheHttpClient.builder.build
    val ssmClient: SsmClient = SsmClient.builder()
      .endpointOverride(URI.create(endpoint))
      .httpClient(httpClient)
      .region(Region.EU_WEST_2)
      .build()
    val getParameterRequest = GetParameterRequest.builder.name(secretPath).withDecryption(true).build
    ssmClient.getParameter(getParameterRequest).parameter().value()
  }

  lazy val service: GraphQlApiService = {
    implicit val backend: SttpBackend[Identity, Any] = configBackend
    implicit val keycloakDeployment: TdrKeycloakDeployment = tdrKeycloakDeployment
    val apiUrl = config.getString("api.url")
    val secretPath = config.getString("auth.clientSecret")
    val ssmEndpoint = config.getString("ssm.endpoint")

    new GraphQlApiService(
      new GraphQLClient[gcs.Data, gcs.Variables](apiUrl),
      keycloakUtils,
      config.getString("auth.clientId"),
      () => getClientSecret(secretPath, ssmEndpoint)
    )
  }

  def apply(
             consignmentSummaryClient: GraphQLClient[gcs.Data, gcs.Variables],
             keycloakUtils: KeycloakUtils,
             clientId: String = "test",
             clientSecretProvider: () => String = () => "test"
  )(implicit
    backend: SttpBackend[Identity, Any],
    tdrKeycloakDeployment: TdrKeycloakDeployment
  ): GraphQlApiService =
    new GraphQlApiService(consignmentSummaryClient, keycloakUtils, clientId, clientSecretProvider)
}
