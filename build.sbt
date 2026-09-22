ThisBuild / scalaVersion := "2.13.18"
ThisBuild / organization := "com.parrot669"
ThisBuild / version := "0.1.0-SNAPSHOT"

lazy val root = (project in file("."))
  .settings(
    name := "parrot669-backend",
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-encoding",
      "utf8"
    ),
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % "3.7.1",
      "org.http4s" %% "http4s-ember-server" % "0.23.30",
      "org.http4s" %% "http4s-dsl" % "0.23.30",
      "org.http4s" %% "http4s-circe" % "0.23.30",
      "io.circe" %% "circe-generic" % "0.14.10",
      "org.tpolecat" %% "doobie-core" % "1.0.0-RC6",
      "org.tpolecat" %% "doobie-hikari" % "1.0.0-RC6",
      "org.tpolecat" %% "doobie-postgres" % "1.0.0-RC6",
      "org.postgresql" % "postgresql" % "42.7.7",
      "org.flywaydb" % "flyway-core" % "11.8.2",
      "org.flywaydb" % "flyway-database-postgresql" % "11.8.2",
      "de.mkammerer" % "argon2-jvm" % "2.12",
      "ch.qos.logback" % "logback-classic" % "1.5.18",
      "org.scalameta" %% "munit" % "1.0.4" % Test
    ),
    Compile / run / fork := true,
    assembly / mainClass := Some("com.parrot669.Main"),
    assembly / assemblyJarName := "parrot669-backend.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", xs @ _*) =>
        xs.map(_.toLowerCase) match {
          case "services" :: _ => MergeStrategy.filterDistinctLines
          case _                => MergeStrategy.discard
        }
      case _ => MergeStrategy.first
    }
  )
