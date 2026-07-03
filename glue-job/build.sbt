import sbtassembly.AssemblyPlugin

val scala2Version = "2.12.18"
val sparkVersion = "3.5.4"
val hadoopVersion = "3.3.5"
val awsSdkVersion = "2.21.0"

lazy val root = (project in file("."))
  .enablePlugins(AssemblyPlugin)
  .settings(
    name := "glue5-spark-job",
    version := "1.0",
    scalaVersion := scala2Version,

    javacOptions ++= Seq("-source", "17", "-target", "17"),

    Test / classLoaderLayeringStrategy := ClassLoaderLayeringStrategy.Flat,
    Test / fork := true,
    Test / javaOptions ++= Seq(
      "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
      "--add-opens=java.base/sun.security.ssl=ALL-UNNAMED",
    ),

    libraryDependencies ++= Seq(
      "org.apache.spark"     %% "spark-sql"            % sparkVersion % Provided,
      "org.apache.hadoop"    %  "hadoop-aws"           % hadoopVersion % Provided,
      "org.apache.hadoop"    %  "hadoop-client"        % hadoopVersion % Provided,
      "org.apache.hadoop"    %  "hadoop-common"        % hadoopVersion % Provided,

      "software.amazon.awssdk" % "s3"                  % awsSdkVersion,
      "software.amazon.awssdk" % "glue"                % awsSdkVersion,
      "software.amazon.awssdk" % "rds"                 % awsSdkVersion,
      "software.amazon.awssdk" % "ssm"                 % awsSdkVersion,
      "software.amazon.awssdk" % "sts"                 % awsSdkVersion,

      "org.postgresql"       %  "postgresql"           % "42.5.4",

      "org.scalatest"        %% "scalatest"            % "3.2.15" % Test,
    ),

    Test / libraryDependencies ++= Seq(
      "org.apache.spark"     %% "spark-sql"            % sparkVersion,
      "org.apache.hadoop"    %  "hadoop-aws"           % hadoopVersion,
      "org.apache.hadoop"    %  "hadoop-client"        % hadoopVersion,
      "org.apache.hadoop"    %  "hadoop-common"        % hadoopVersion,
    ),

    libraryDependencySchemes += "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always,

    dependencyOverrides ++= Seq(
      "com.fasterxml.jackson.core" % "jackson-core"    % "2.15.2",
      "com.fasterxml.jackson.core" % "jackson-databind" % "2.15.2",
      "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.15.2",
    ),

    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", xs @ _*) =>
        xs match {
          case "MANIFEST.MF" :: Nil  => MergeStrategy.discard
          case "services" :: _       => MergeStrategy.concat
          case _                     => MergeStrategy.discard
        }
      case "reference.conf"          => MergeStrategy.concat
      case "log4j.properties"        => MergeStrategy.concat
      case _                         => MergeStrategy.first
    },
  )
