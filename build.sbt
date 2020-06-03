import scala.sys.process._

val gitCommitHash = settingKey[String]("The git commit hash")
gitCommitHash := Process("git rev-parse HEAD").lineStream.head
val gitCommitDate =  settingKey[String]("The git commit date")
gitCommitDate := Process("git log -1  --pretty=format:%cd --date=format:%Y%m%d").lineStream.head
val gitBranch = settingKey[String]("The git branch")
gitBranch := Process("git rev-parse --abbrev-ref HEAD").lineStream.head.replaceAll("/", "-")
val shortVersion = settingKey[String]("The short version string")
shortVersion := gitCommitDate.value
val longVersion = settingKey[String]("The long version string")
longVersion := "%s-%s-%s".format(gitCommitDate.value, gitBranch.value, gitCommitHash.value.take(8))

def createMscFile(baseDir: java.io.File, version: String, exePath: String, mznLibPath: String): java.io.File = {
    val source = new java.io.File(baseDir / "resources" / "mzn", "yuck.msc.in")
    val sink = java.io.File.createTempFile("yuck-", ".msc")
    val writer = new java.io.FileWriter(sink)
    for (line <- scala.io.Source.fromFile(source).getLines) {
        writer.write(
            "%s\n".format(
                line.replace("VERSION", version)
                    .replace("EXE_PATH", exePath)
                    .replace("MZN_LIB_PATH", mznLibPath)))
    }
    writer.close
    sink
}

name := "yuck"
description := "Yuck is a local-search constraint solver with FlatZinc interface"
homepage := Some(url("https://github.com/informarte/yuck"))
startYear := Some(2013)
// For creating a Debian package, the version must start with a digit.
version := (if (gitBranch.value == "master") shortVersion.value else longVersion.value)
maintainer := "Michael Marte <informarte@freenet.de>"
packageSummary := "FlatZinc interpreter"
packageDescription := description.value

EclipseKeys.withSource := true
EclipseKeys.withJavadoc := true
EclipseKeys.eclipseOutput := Some("target/eclipse")

javacOptions ++= Seq("-source", "1.8", "-target", "1.8")

scalaVersion := "2.13.1"

scalacOptions ++= Seq(
    "-target:jvm-1.8",
    "-deprecation",
    "-unchecked",
    "-feature"
)

scalaSource in Compile := baseDirectory.value / "src/main"
javaSource in Compile := baseDirectory.value / "src/main"
scalaSource in Test := baseDirectory.value / "src/test"
javaSource in Test := baseDirectory.value / "src/test"

libraryDependencies ++= Seq(
    "com.conversantmedia" % "rtree" % "1.0.5",
    "com.github.scopt" %% "scopt" % "3.7.1",
    "com.novocode" % "junit-interface" % "0.11" % "test",
    "io.spray" %% "spray-json" % "1.3.5",
    "junit" % "junit" % "4.12" % "test",
    "org.jgrapht" % "jgrapht-core" % "1.4.0",
    "org.jgrapht" % "jgrapht-io" % "1.4.0" % "test",
    "org.scala-lang.modules" %% "scala-parser-combinators" % "1.1.2",
    "org.scalamock" %% "scalamock" % "4.4.0" % "test",
)

// See https://github.com/sbt/junit-interface/issues/66 for why it does not work!
testOptions in Test := Seq(Tests.Filter(s => s.endsWith("UnitTestSuite")))

fork := true
javaOptions ++= Seq("-server", "-Xmx2G", "-XX:+UseParallelGC", "-XX:+AggressiveHeap")

mainClass in (Compile, run) := Some("yuck.flatzinc.runner.FlatZincRunner")
mainClass in (Compile, packageBin) := Some("yuck.flatzinc.runner.FlatZincRunner")

enablePlugins(JavaAppPackaging)

enablePlugins(UniversalPlugin)

val yuckMscFileForUniversalPackage = taskKey[java.io.File]("Create yuck.msc file for universal package")
yuckMscFileForUniversalPackage :=
    createMscFile(baseDir = baseDirectory.value, version = gitCommitDate.value, exePath = "../bin/yuck", mznLibPath = "lib")

mappings in Universal +=
    (baseDirectory.value / "doc" / "copyright" -> "doc/copyright")
mappings in Universal ++=
    (baseDirectory.value / "doc" / "licenses")
    .listFiles
    .toStream
    .map(file => file -> ("doc/licenses/" + file.getName))
mappings in Universal ++=
    (baseDirectory.value / "resources" / "mzn" / "lib" / "yuck")
    .listFiles
    .toStream
    .map(file => file -> ("mzn/lib/" + file.getName))
mappings in Universal += {
    val file = yuckMscFileForUniversalPackage.value
    file -> "mzn/yuck.msc"
}

enablePlugins(DebianPlugin)

val yuckMscFileForDebianPackage = taskKey[java.io.File]("Create yuck.msc file for Debian package")
yuckMscFileForDebianPackage :=
    createMscFile(baseDir = baseDirectory.value, version = gitCommitDate.value, exePath = "/usr/bin/yuck", mznLibPath = "/usr/share/yuck/mzn/lib")

debianPackageDependencies in Debian ++= Seq("default-jre-headless (>= 1.8)")
debianSection in Debian := "universe/interpreters"
linuxPackageMappings in Debian += {
    val file = yuckMscFileForDebianPackage.value
    packageMapping(file -> "/usr/share/minizinc/solvers/yuck.msc").withPerms("0644")
}

enablePlugins(BuildInfoPlugin)
buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion, gitBranch, gitCommitHash)
buildInfoPackage := "yuck"
