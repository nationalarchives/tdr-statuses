import sbt._

object Dependencies {
  private val circeVersion = "0.14.15"

  private val testContainersVersion = "0.44.1"
  private val awsVersion = "2.42.13"
  private val doobieVersion = "1.0.0-RC11"

  lazy val awsS3 = "software.amazon.awssdk" % "s3" % awsVersion
  lazy val awsSsm = "software.amazon.awssdk" % "ssm" % awsVersion
  lazy val snsUtils = "uk.gov.nationalarchives" %% "sns-utils" % "0.1.319"
  lazy val backendCheckUtils = "uk.gov.nationalarchives" %% "tdr-backend-checks-utils" % "0.1.192"
  lazy val circeCore = "io.circe" %% "circe-core" % circeVersion
  lazy val circeParser = "io.circe" %% "circe-parser" % circeVersion
  lazy val circeGeneric = "io.circe" %% "circe-generic" % circeVersion
  lazy val scalaTest = "org.scalatest" %% "scalatest" % "3.2.19"
  lazy val testContainersScala = "com.dimafeng" %% "testcontainers-scala-scalatest" % testContainersVersion
  lazy val testContainersPostgres = "com.dimafeng" %% "testcontainers-scala-postgresql" % testContainersVersion
  lazy val mockito = "org.mockito" %% "mockito-scala" % "2.1.0"
  lazy val wiremock = "com.github.tomakehurst" % "wiremock" % "3.0.1"
  lazy val metadataSchema =  "uk.gov.nationalarchives" %% "da-metadata-schema" % "0.0.122"
  lazy val catsEffect = "org.typelevel" %% "cats-effect" % "3.6.3"
  lazy val graphqlClient = "uk.gov.nationalarchives" %% "tdr-graphql-client" % "0.0.283"
  lazy val generatedGraphql = "uk.gov.nationalarchives" %% "tdr-generated-graphql" % "0.0.461"
  lazy val authUtils = "uk.gov.nationalarchives" %% "tdr-auth-utils" % "0.0.277"
  lazy val typesafeConfig = "com.typesafe" % "config" % "1.4.6"
  lazy val catsEffectTesting = "org.typelevel" %% "cats-effect-testing-scalatest" % "1.8.0"
}
