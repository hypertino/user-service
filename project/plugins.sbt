resolvers ++= Seq(
  Resolver.sonatypeRepo("public")
)

addSbtPlugin("com.hypertino" % "hyperbus-raml-sbt-plugin" % "0.2-SNAPSHOT")

addSbtPlugin("com.jsuereth" % "sbt-pgp" % "1.0.0")

addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "1.1")
