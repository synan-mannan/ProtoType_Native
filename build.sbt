name := "native-poller"

ThisBuild / version := "0.1.0"
ThisBuild / scalaVersion := "3.4.1"
ThisBuild / organization := "com.example"

lazy val core = project
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % "3.5.4",
      "com.github.jnr" % "jnr-ffi" % "2.2.15",
      "com.github.jnr" % "jnr-posix" % "3.1.19"
    )
  )

lazy val example = project
  .dependsOn(core)
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % "3.5.4",
      "com.github.jnr" % "jnr-posix" % "3.1.19"
    )
  )
