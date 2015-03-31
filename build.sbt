lazy val root = (project in file(".")).
  settings(
    name := "shmu",
    version := "1.0",
    scalaVersion := "2.11.6",

    scalacOptions ++= Seq("-deprecation", "-unchecked"),

    resolvers ++= Seq(
      "softprops-maven" at "http://dl.bintray.com/content/softprops/maven",
      "JCenter" at "http://jcenter.bintray.com/"
    ),

    libraryDependencies ++= Seq(
      "me.lessis" %% "base64" % "0.2.0",
      "com.typesafe.akka" %% "akka-actor" % "2.3.9",
      "org.slf4j" % "slf4j-api" % "1.7.10",
      "org.slf4j" % "slf4j-simple" % "1.7.10"
    )
  )
