import sbt._


object Dependencies {
  private val circeVersion = "0.14.3"
  private val testContainersVersion = "0.40.11"
  private val awsVersion = "2.18.24"

  lazy val awsRds = "software.amazon.awssdk" % "rds" % awsVersion
  lazy val awsSsm = "software.amazon.awssdk" % "ssm" % awsVersion
  lazy val circeCore = "io.circe" %% "circe-core" % circeVersion
  lazy val circeParser = "io.circe" %% "circe-parser" % circeVersion
  lazy val circeGeneric = "io.circe" %% "circe-generic" % circeVersion
  lazy val pureConfig = "com.github.pureconfig" %% "pureconfig" % "0.17.2"
  lazy val skunk = "org.tpolecat" %% "skunk-core" % "0.3.2"
  lazy val scalaTest = "org.scalatest" %% "scalatest" % "3.2.14"
  lazy val catsTesting = "org.typelevel" %% "cats-effect-testing-scalatest" % "1.4.0"
  lazy val testContainersScala = "com.dimafeng" %% "testcontainers-scala-scalatest" % testContainersVersion
  lazy val testContainersPostgres = "com.dimafeng" %% "testcontainers-scala-postgresql" % testContainersVersion
  lazy val mockito = "org.mockito" %% "mockito-scala" % "1.17.12"
}
