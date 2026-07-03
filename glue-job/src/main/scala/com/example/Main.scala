package com.example

import org.apache.spark.sql.SparkSession

object Main {
  def main(args: Array[String]): Unit = {
    if (args.length < 5) {
      System.err.println(
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
      println(s"Job completed successfully. Output: ${args(4)}")
    } finally {
      spark.stop()
    }
  }
}
