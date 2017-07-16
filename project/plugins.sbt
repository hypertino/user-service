resolvers ++= Seq(
  Resolver.sonatypeRepo("public")
)

addSbtPlugin("com.hypertino" % "hyperbus-raml-sbt-plugin" % "0.2-SNAPSHOT")
