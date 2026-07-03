package com.example

import org.apache.spark.sql.SparkSession
import org.slf4j.LoggerFactory

object Main {
  private val log = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    if (args.length < 5) {
      log.error(
        "Usage: Main <jdbcUrl> <jdbcUser> <jdbcPassword> <jdbcQuery> <outputPath> [apiEndpoint]"
      )
      System.exit(1)
    }

    val spark = SparkSession.builder()
      .appName("EmpToS3")
      .getOrCreate()

    try {
      val apiEndpoint = if (args.length >= 6) args(5) else ""
      EmpToS3Core.run(spark, args(0), args(1), args(2), args(3), args(4), apiEndpoint)
      log.info("Job completed successfully. Output: {}", args(4))
    } finally {
      spark.stop()
    }
  }
}
