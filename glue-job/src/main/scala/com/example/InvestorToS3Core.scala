package com.example

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions.lit
import org.slf4j.LoggerFactory
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ssm.SsmClient
import software.amazon.awssdk.services.ssm.model.GetParameterRequest

import java.net.{HttpURLConnection, URI}
import scala.io.Source

object InvestorToS3Core {
  private val log = LoggerFactory.getLogger(getClass)

  private val ValidSegments = Set("Wealth", "Premium", "Retail", "Corporate")

  def run(spark: SparkSession, jdbcUrl: String, jdbcUser: String, jdbcPassword: String, outputPath: String, segment: String, apiEndpoint: String = ""): Unit = {
    require(ValidSegments.contains(segment), s"Invalid segment '$segment'. Must be one of: ${ValidSegments.mkString(", ")}")
    val query = s"SELECT customer_id, full_name, annual_income_usd, customer_segment, domicile_currency, join_date FROM public.investors WHERE customer_segment = '$segment' ORDER BY customer_id"
    val df = spark.read.format("jdbc")
      .option("url", jdbcUrl)
      .option("user", jdbcUser)
      .option("password", jdbcPassword)
      .option("driver", "org.postgresql.Driver")
      .option("query", query)
      .load()
      .coalesce(1)

    val effectiveApiEndpoint = if (apiEndpoint.nonEmpty) apiEndpoint else resolveApiEndpointFromSsm()

    val enriched = if (effectiveApiEndpoint.nonEmpty) {
      log.info(s"Fetching segment info from API: $effectiveApiEndpoint/segment/$segment")
      val segmentInfo = callSegmentApi(effectiveApiEndpoint, segment)
      log.info("Segment API response: {}", segmentInfo)
      df.withColumn("segment_status", lit(segmentInfo))
    } else {
      log.info("No API endpoint configured, skipping segment check")
      df.withColumn("segment_status", lit("unknown"))
    }

    enriched.write
      .mode("overwrite")
      .option("delimiter", "|")
      .option("header", "true")
      .csv(outputPath)
  }

  private def callSegmentApi(apiEndpoint: String, segment: String): String = {
    try {
      val url = URI.create(s"$apiEndpoint/segment/$segment").toURL
      val conn = url.openConnection().asInstanceOf[HttpURLConnection]
      conn.setRequestMethod("GET")
      conn.setConnectTimeout(5000)
      conn.setReadTimeout(5000)
      val response = Source.fromInputStream(conn.getInputStream).mkString
      conn.disconnect()
      response
    } catch {
      case e: Exception =>
        log.warn("Segment API call failed: {}", e.getMessage)
        "unknown"
    }
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
        val req = GetParameterRequest.builder().name("/investor/api/endpoint").build()
        val value = ssm.getParameter(req).parameter().value()
        log.info("Resolved API endpoint from SSM: {}", value)
        value
      } finally {
        ssm.close()
      }
    } catch {
      case e: Exception =>
        log.error("Failed to read SSM parameter /investor/api/endpoint: {}", e.getMessage)
        ""
    }
  }
}
