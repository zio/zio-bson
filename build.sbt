enablePlugins(ZioSbtEcosystemPlugin, ZioSbtCiPlugin)

inThisBuild(
  List(
    name               := "ZIO Bson",
    organization       := "dev.zio",
    zioVersion         := "2.1.16",
    ciEnabledBranches  := Seq("main"),
    crossScalaVersions := Seq(scala213.value),
    scalaVersion       := scala213.value,
    developers         := List(
      Developer(
        "jdegoes",
        "John De Goes",
        "john@degoes.net",
        url("http://degoes.net")
      )
    )
  )
)

val bsonVersion                  = "5.6.2"
val scalaCollectionCompatVersion = "2.13.0"
val magnoliaVersion              = "1.1.10"

lazy val root = project
  .in(file("."))
  .settings(
    publish / skip := true
  )
  .aggregate(
    `zio-bson`,
    docs,
    `zio-bson-magnolia`
  )

lazy val `zio-bson` = project
  .settings(stdSettings())
  .settings(buildInfoSettings("zio.bson"))
  .settings(enableZIO())
  .settings(
    crossScalaVersions := Seq(scala212.value, scala213.value, scala3.value),
    libraryDependencies ++= Seq(
      "org.mongodb"             % "bson"                    % bsonVersion,
      "org.scala-lang.modules" %% "scala-collection-compat" % scalaCollectionCompatVersion
    ),
    scalaReflectTestSettings
  )

lazy val `zio-bson-magnolia` = project
  .dependsOn(`zio-bson` % "compile->compile;test->test")
  .settings(stdSettings())
  .settings(buildInfoSettings("zio.bson.magnolia"))
  .settings(enableZIO())
  .settings(
    crossScalaVersions := Seq(scala213.value),
    libraryDependencies ++= Seq(
      "dev.zio"                      %% "zio-test-magnolia"       % zioVersion.value % Test,
      "com.softwaremill.magnolia1_2" %% "magnolia"                % magnoliaVersion,
      "org.scala-lang.modules"       %% "scala-collection-compat" % scalaCollectionCompatVersion
    ),
    scalaReflectTestSettings,
    macroDefinitionSettings,
    scalacOptions -= "-Xfatal-warnings"
  )

lazy val docs = project
  .in(file("zio-bson-docs"))
  .dependsOn(`zio-bson`, `zio-bson-magnolia`)
  .settings(stdSettings())
  .settings(
    crossScalaVersions                         := Seq(scala213.value),
    moduleName                                 := "zio-bson-docs",
    scalacOptions -= "-Yno-imports",
    scalacOptions -= "-Xfatal-warnings",
    scalacOptions += "-Ymacro-annotations",
    projectName                                := "ZIO Bson",
    mainModuleName                             := (`zio-bson` / moduleName).value,
    projectStage                               := ProjectStage.Development,
    ScalaUnidoc / unidoc / unidocProjectFilter := inProjects(`zio-bson`, `zio-bson-magnolia`),
    readmeContribution +=
      """|
         |#### TL;DR
         |
         |Before you submit a PR, make sure your tests are passing, and that the code is properly formatted
         |
         |```
         |sbt prepare
         |
         |sbt test
         |```""".stripMargin
  )
  .enablePlugins(WebsitePlugin)
