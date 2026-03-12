package uk.gov.nationalarchives

import cats.effect.testing.scalatest.AsyncIOSpec
import com.nimbusds.oauth2.sdk.token.BearerAccessToken
import graphql.codegen.GetConsignmentSummary.{getConsignmentSummary => gcs}
import org.mockito.ArgumentMatchers._
import org.mockito.MockitoSugar
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import sttp.client3.{HttpURLConnectionBackend, Identity, SttpBackend}
import uk.gov.nationalarchives.BackendCheckUtils.FileCheckResults
import uk.gov.nationalarchives.services.{ConsignmentDetails, ConsignmentSummaryNotFound, GraphQlApiService}
import uk.gov.nationalarchives.tdr.{GraphQLClient, GraphQlResponse}
import uk.gov.nationalarchives.tdr.keycloak.{KeycloakUtils, TdrKeycloakDeployment}

import java.util.UUID
import scala.concurrent.Future

class GraphQlApiServiceSpec extends AsyncWordSpec with AsyncIOSpec with Matchers with MockitoSugar {

  implicit val backend: SttpBackend[Identity, Any] = HttpURLConnectionBackend()
  implicit val tdrKeycloakDeployment: TdrKeycloakDeployment = TdrKeycloakDeployment("https://auth", "tdr", 3600)

  private val consignmentId: UUID = UUID.randomUUID()
  private val userId: UUID = UUID.randomUUID()

  private val file = uk.gov.nationalarchives.BackendCheckUtils.File(
    consignmentId,
    UUID.randomUUID(),
    userId,
    "standard",
    "FileSize",
    "Checksum": String,
    "originalPath": String,
    Some("3SourceBucket"): Option[String],
    Some("s3SourceBucketKey"),
    FileCheckResults(List.empty, List.empty, List.empty)
  )

  private def createService(
    gcResponse: GraphQlResponse[gcs.Data]
  ): GraphQlApiService = {
    val mockConsignmentClient     = mock[GraphQLClient[gcs.Data, gcs.Variables]]
    val mockKeycloak              = mock[KeycloakUtils]

    when(mockKeycloak.serviceAccountToken(anyString(), anyString())(any(), any(), any()))
      .thenReturn(Future.successful(new BearerAccessToken("token")))

    when(mockConsignmentClient.getResult(any[BearerAccessToken], any(), any())(any(), any()))
      .thenReturn(Future.successful(gcResponse))


    GraphQlApiService(mockConsignmentClient, mockKeycloak)
  }

  "getConsignmentDetails" should {
    "return ConsignmentDetails when getConsignmentSummary returns data" in {
      val gcsData = gcs.Data(Some(gcs.GetConsignment(
        seriesName = None,
        consignmentReference = "TDR-2025-ABC",
        totalFiles = 100,
        transferringBodyName = Some("Test Body")
      )))

        val service = createService(
        GraphQlResponse(Some(gcsData), Nil)
      )

      service.getConsignmentDetails(file).asserting { result =>
        result shouldBe ConsignmentDetails(
          consignmentId = consignmentId,
          consignmentType = "standard",
          consignmentReference = "TDR-2025-ABC",
          transferringBody = Some("Test Body"),
          userId = userId
        )
      }
    }

    "raise ConsignmentSummaryNotFound when GetConsignment returns no consignment" in {
      val gcsData  = gcs.Data(None)

      val service = createService(
        GraphQlResponse(Some(gcsData), Nil)
      )

      service.getConsignmentDetails(file).attempt.asserting { result =>
        result.left.toOption.get shouldBe a[ConsignmentSummaryNotFound]
      }
    }
  }
}
