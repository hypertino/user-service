crossScalaVersions := Seq("2.12.3", "2.11.11")

scalaVersion := crossScalaVersions.value.head

lazy val `user-service` = project in file(".") enablePlugins Raml2Hyperbus settings (
    name := "user-service",
    version := "0.4-SNAPSHOT",
    organization := "com.hypertino",
    resolvers ++= Seq(
      Resolver.sonatypeRepo("public")
    ),
    libraryDependencies ++= Seq(
      "com.hypertino" %% "hyperbus" % "0.4-SNAPSHOT",
      "com.hypertino" %% "hyperbus-t-inproc" % "0.4-SNAPSHOT" % "test",
      "com.hypertino" %% "service-control" % "0.4.1",
      "com.hypertino" %% "service-config" % "0.2.0" % "test",
      "ch.qos.logback" % "logback-classic" % "1.2.3" % "test",
      "org.scalamock" %% "scalamock-scalatest-support" % "3.5.0" % "test",
      compilerPlugin("org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full)
    ),
    ramlHyperbusSources := Seq(
      ramlSource(
        path = "api/user-service-api/user.raml",
        packageName = "com.hypertino.user.api",
        isResource = false
      ),
      ramlSource(
        path = "api/hyper-storage-service-api/hyperstorage.raml",
        packageName = "com.hypertino.user.apiref.hyperstorage",
        isResource = false
      ),
      ramlSource(
        path = "api/auth-basic-service-api/auth-basic.raml",
        packageName = "com.hypertino.user.apiref.authbasic",
        isResource = false
      )
    )
)
