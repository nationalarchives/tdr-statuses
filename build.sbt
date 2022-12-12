import Dependencies._

ThisBuild / scalaVersion     := "2.13.8"
ThisBuild / version          := "0.1.0-SNAPSHOT"
ThisBuild / organization     := "uk.gov.nationalarchives"

lazy val root = (project in file("."))
  .settings(
    name := "tdr-statuses",
    libraryDependencies ++= Seq(
      awsRds,
      awsSsm,
      circeCore,
      circeParser,
      circeGeneric,
      pureConfig,
      skunk,
      catsTesting % Test,
      mockito % Test,
      scalaTest % Test,
      testContainersScala % Test,
      testContainersPostgres % Test,
      wiremock % Test
    ),
    assembly / assemblyJarName := "statuses.jar"
  )
