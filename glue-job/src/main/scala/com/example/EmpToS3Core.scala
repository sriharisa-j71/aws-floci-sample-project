package com.example

import org.apache.spark.sql.SparkSession
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ssm.SsmClient
import software.amazon.awssdk.services.ssm.model.GetParameterRequest

import java.net.{HttpURLConnection, URI}
import scala.io.Source

object EmpToS3Core {
  def run(spark: SparkSession, jdbcUrl: String, jdbcUser: String, jdbcPassword: String, jdbcQuery: String, outputPath: String, apiEndpoint: String = ""): Unit = {
    val df = spark.read.format("jdbc")
      .option("url", jdbcUrl)
      .option("user", jdbcUser)
      .option("password", jdbcPassword)
      .option("driver", "org.postgresql.Driver")
      .option("query", jdbcQuery)
      .load()
      .coalesce(1)

    val effectiveApiEndpoint = if (apiEndpoint.nonEmpty) apiEndpoint else resolveApiEndpointFromSsm()

    if (effectiveApiEndpoint.nonEmpty) {
      import df.sparkSession.implicits._
      println(s"=== Checking employees via API: $effectiveApiEndpoint ===")
      df.foreach { row =>
        val empId = row.getAs[java.lang.Integer]("emp_id")
        val name  = row.getAs[String]("emp_name")
        try {
          val url = URI.create(s"$effectiveApiEndpoint/employee/$empId").toURL
          val conn = url.openConnection().asInstanceOf[HttpURLConnection]
          conn.setRequestMethod("GET")
          conn.setConnectTimeout(5000)
          conn.setReadTimeout(5000)
          val response = Source.fromInputStream(conn.getInputStream).mkString
          println(s"  Employee $empId ($name): $response")
          conn.disconnect()
        } catch {
          case e: Exception => println(s"  Employee $empId ($name): API call failed - ${e.getMessage}")
        }
      }
    } else {
      println("=== No API endpoint configured, skipping employee check ===")
    }

    df.write
      .mode("overwrite")
      .option("delimiter", "|")
      .option("header", "true")
      .csv(outputPath)
  }

  private def resolveApiEndpointFromSsm(): String = {
    try {
      val endpointStr = sys.env.getOrElse("AWS_ENDPOINT_URL", "http://floci:4566")
      val regionStr  = sys.env.getOrElse("AWS_DEFAULT_REGION", "us-east-1")

      val ssm = SsmClient.builder()
        .region(Region.of(regionStr))
        .credentialsProvider(DefaultCredentialsProvider.create())
        .endpointOverride(URI.create(endpointStr))
        .build()

      try {
        val req = GetParameterRequest.builder().name("/emp/api/endpoint").build()
        val value = ssm.getParameter(req).parameter().value()
        println(s"Resolved API endpoint from SSM: $value")
        value
      } finally {
        ssm.close()
      }
    } catch {
      case e: Exception =>
        println(s"Failed to read SSM parameter /emp/api/endpoint: ${e.getMessage}")
        ""
    }
  }
}
