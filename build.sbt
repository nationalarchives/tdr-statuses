import Dependencies._

ThisBuild / scalaVersion     := "2.13.16"
ThisBuild / version          := "0.1.0-SNAPSHOT"
ThisBuild / organization     := "uk.gov.nationalarchives"

lazy val root = (project in file("."))
  .settings(
    name := "tdr-statuses",
    libraryDependencies ++= Seq(
      awsRds,
      awsSsm,
      awsS3,
      backendCheckUtils,
      circeCore,
      circeParser,
      circeGeneric,
      doobie,
      postgres,
      pureConfig,
      catsTesting % Test,
      mockito % Test,
      scalaTest % Test,
      testContainersScala % Test,
      testContainersPostgres % Test,
      wiremock % Test
    ),
    assembly / assemblyJarName := "statuses.jar"
  )

(assembly / assemblyMergeStrategy) := {
  case PathList("META-INF", xs@_*) => MergeStrategy.discard
  case _ => MergeStrategy.first
}

Test / fork := true
Test / javaOptions += s"-Dconfig.file=${sourceDirectory.value}/test/resources/application.conf"
(Test / fork) := true
(Test / javaOptions) += s"-Dconfig.file=${sourceDirectory.value}/test/resources/application.conf"
(Test / envVars) := Map("AWS_ACCESS_KEY_ID" -> "test", "AWS_SECRET_ACCESS_KEY" -> "test", "S3_ENDPOINT" -> "http://localhost:9005")
