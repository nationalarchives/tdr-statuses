import sbt._

object Dependencies {
  private val circeVersion = "0.14.6"
  private val testContainersVersion = "0.41.3"
  private val awsVersion = "2.25.11"
  private val doobieVersion = "1.0.0-RC5"

  lazy val awsRds = "software.amazon.awssdk" % "rds" % awsVersion
  lazy val awsSsm = "software.amazon.awssdk" % "ssm" % awsVersion
  lazy val awsS3 = "software.amazon.awssdk" % "s3" % awsVersion
  lazy val backendCheckUtils = "uk.gov.nationalarchives" %% "tdr-backend-checks-utils" % "0.1.69"
  lazy val circeCore = "io.circe" %% "circe-core" % circeVersion
  lazy val circeParser = "io.circe" %% "circe-parser" % circeVersion
  lazy val circeGeneric = "io.circe" %% "circe-generic" % circeVersion
  lazy val postgres = "org.postgresql" % "postgresql" % "42.7.2"
  lazy val pureConfig = "com.github.pureconfig" %% "pureconfig" % "0.17.6"
  lazy val doobie = "org.tpolecat" %% "doobie-core" % doobieVersion
  lazy val doobiePostgres = "org.tpolecat" %% "doobie-postgres"  % doobieVersion
  lazy val scalaTest = "org.scalatest" %% "scalatest" % "3.2.18"
  lazy val catsTesting = "org.typelevel" %% "cats-effect-testing-scalatest" % "1.5.0"
  lazy val testContainersScala = "com.dimafeng" %% "testcontainers-scala-scalatest" % testContainersVersion
  lazy val testContainersPostgres = "com.dimafeng" %% "testcontainers-scala-postgresql" % testContainersVersion
  lazy val mockito = "org.mockito" %% "mockito-scala" % "1.17.30"
  lazy val wiremock = "com.github.tomakehurst" % "wiremock" % "3.0.1"
}
