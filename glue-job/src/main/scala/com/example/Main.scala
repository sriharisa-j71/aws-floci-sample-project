package com.example

import org.apache.spark.sql.SparkSession
import org.slf4j.LoggerFactory

object Main {
  private val log = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    if (args.length < 5) {
      log.error(
        "Usage: Main <jdbcUrl> <jdbcUser> <jdbcPassword> <outputPath> <segment> [apiEndpoint]"
      )
      System.exit(1)
    }

    val spark = SparkSession.builder()
      .appName("InvestorToS3")
      .getOrCreate()

    try {
      val apiEndpoint = if (args.length >= 6) args(5) else ""
      InvestorToS3Core.run(spark, args(0), args(1), args(2), args(3), args(4), apiEndpoint)
      log.info("Job completed successfully. Output: {}", args(3))
    } finally {
      spark.stop()
    }
  }
}
