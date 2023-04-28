import BuildHelper._

inThisBuild(
  List(
    organization := "dev.zio",
    homepage := Some(url("https://zio.github.io/zio-bson/")),
    licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    developers := List(
      Developer(
        "jdegoes",
        "John De Goes",
        "john@degoes.net",
        url("http://degoes.net")
      )
    ),
    pgpPassphrase := sys.env.get("PGP_PASSWORD").map(_.toArray),
    pgpPublicRing := file("/tmp/public.asc"),
    pgpSecretRing := file("/tmp/secret.asc")
  )
)

addCommandAlias("prepare", "fix; fmt")
addCommandAlias("fmt", "all scalafmtSbt scalafmt test:scalafmt")
addCommandAlias("fix", "; all compile:scalafix test:scalafix; all scalafmtSbt scalafmtAll")
addCommandAlias("check", "; scalafmtSbtCheck; scalafmtCheckAll; compile:scalafix --check; test:scalafix --check")

val zioVersion                   = "2.0.10"
val bsonVersion                  = "4.9.1"
val scalaCollectionCompatVersion = "2.10.0"
val magnoliaVersion              = "1.1.3"

lazy val root = project
  .in(file("."))
  .settings(
    publish / skip := true
  )
  .aggregate(
    zioBson,
    docs,
    zioBsonMagnolia
  )

lazy val zioBson = project
  .in(file("zio-bson"))
  .settings(stdSettings("zio-bson"))
  .settings(crossProjectSettings)
  .settings(buildInfoSettings("zio.bson"))
  .settings(
    libraryDependencies ++= Seq(
      "dev.zio"                %% "zio"                     % zioVersion,
      "dev.zio"                %% "zio-test"                % zioVersion % Test,
      "dev.zio"                %% "zio-test-sbt"            % zioVersion % Test,
      "org.mongodb"             % "bson"                    % bsonVersion,
      "org.scala-lang.modules" %% "scala-collection-compat" % scalaCollectionCompatVersion
    ),
    scalaReflectTestSettings
  )
  .settings(testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"))
  .enablePlugins(BuildInfoPlugin)

lazy val zioBsonMagnolia = project
  .in(file("zio-bson-magnolia"))
  .dependsOn(zioBson % "compile->compile;test->test")
  .settings(stdSettings("zio-bson", crossCompile = false))
  .settings(crossProjectSettings)
  .settings(buildInfoSettings("zio.bson.magnolia"))
  .settings(
    libraryDependencies ++= Seq(
      "dev.zio"                      %% "zio"                     % zioVersion,
      "dev.zio"                      %% "zio-test"                % zioVersion % Test,
      "dev.zio"                      %% "zio-test-magnolia"       % zioVersion % Test,
      "dev.zio"                      %% "zio-test-sbt"            % zioVersion % Test,
      "com.softwaremill.magnolia1_2" %% "magnolia"                % magnoliaVersion,
      "org.scala-lang.modules"       %% "scala-collection-compat" % scalaCollectionCompatVersion
    ),
    scalaReflectTestSettings,
    macroDefinitionSettings
  )
  .settings(testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"))
  .enablePlugins(BuildInfoPlugin)

lazy val docs = project
  .in(file("zio-bson-docs"))
  .dependsOn(zioBson, zioBsonMagnolia)
  .settings(stdSettings("zio-bson-docs", crossCompile = false))
  .settings(
    moduleName := "zio-bson-docs",
    scalacOptions -= "-Yno-imports",
    scalacOptions -= "-Xfatal-warnings",
    scalacOptions += "-Ymacro-annotations",
    projectName := "ZIO Bson",
    mainModuleName := (zioBson / moduleName).value,
    projectStage := ProjectStage.Development,
    ScalaUnidoc / unidoc / unidocProjectFilter := inProjects(zioBson, zioBsonMagnolia),
    docsPublishBranch := "main",
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
