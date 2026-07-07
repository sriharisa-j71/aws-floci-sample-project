import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterAll

import com.example.InvestorToS3Core
import org.apache.spark.sql.SparkSession
import org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider

import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{CreateBucketRequest, DeleteBucketRequest, DeleteObjectRequest, ListObjectsV2Request}
import software.amazon.awssdk.services.rds.RdsClient
import software.amazon.awssdk.services.rds.model.{CreateDbInstanceRequest, DeleteDbInstanceRequest, DescribeDbInstancesRequest, Endpoint}

import java.net.URI
import java.sql.DriverManager
import scala.collection.JavaConverters._

class EmpToS3JobSimulation extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val endpoint = URI.create("http://localhost:4566")
  private val region = Region.US_EAST_1
  private val creds = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))

  private var spark: SparkSession = _
  private var s3: S3Client = _
  private var rds: RdsClient = _

  private val outputBucket = "emp-output"
  private val dbInstanceId = "emp-db"
  private val segment      = "Wealth"

  private val jdbcUser     = "admin"
  private val jdbcPassword = "secret123"

  private var jdbcUrl: String = _

  override def beforeAll(): Unit = {
    spark = SparkSession.builder()
      .appName("EmpToS3Simulation")
      .master("local[*]")
      .config("spark.hadoop.fs.s3a.impl", "org.apache.hadoop.fs.s3a.S3AFileSystem")
      .config("spark.hadoop.fs.s3a.endpoint", "http://localhost:4566")
      .config("spark.hadoop.fs.s3a.path.style.access", "true")
      .config("spark.hadoop.fs.s3a.connection.ssl.enabled", "false")
      .config("spark.hadoop.fs.s3a.aws.credentials.provider", classOf[SimpleAWSCredentialsProvider].getName)
      .config("spark.hadoop.fs.s3a.access.key", "test")
      .config("spark.hadoop.fs.s3a.secret.key", "test")
      .config("spark.sql.catalogImplementation", "in-memory")
      .getOrCreate()

    s3 = S3Client.builder()
      .region(region).credentialsProvider(creds)
      .endpointOverride(endpoint).forcePathStyle(true).build()

    rds = RdsClient.builder()
      .region(region).credentialsProvider(creds)
      .endpointOverride(endpoint).build()

    createS3Bucket(outputBucket)
    createRdsInstance()
    jdbcUrl = waitForRdsAndGetJdbcUrl()
    seedInvestorsTable()
  }

  private def createS3Bucket(name: String): Unit = {
    try {
      s3.createBucket(CreateBucketRequest.builder().bucket(name).build())
      println(s"S3 bucket created: $name")
    } catch {
      case e: Exception => println(s"S3 bucket $name may already exist: ${e.getMessage}")
    }
  }

  private def createRdsInstance(): Unit = {
    try {
      rds.createDBInstance(
        CreateDbInstanceRequest.builder()
          .dbInstanceIdentifier(dbInstanceId)
          .dbInstanceClass("db.t3.micro")
          .engine("postgres")
          .masterUsername(jdbcUser)
          .masterUserPassword(jdbcPassword)
          .allocatedStorage(20)
          .build()
      )
      println(s"RDS instance created: $dbInstanceId")
    } catch {
      case e: Exception => println(s"RDS instance may already exist: ${e.getMessage}")
    }
  }

  private def waitForRdsAndGetJdbcUrl(): String = {
    var endpoint: Endpoint = null
    var attempts = 0
    while (endpoint == null && attempts < 30) {
      try {
        val resp = rds.describeDBInstances(
          DescribeDbInstancesRequest.builder()
            .dbInstanceIdentifier(dbInstanceId)
            .build()
        )
        val instances = resp.dbInstances()
        if (!instances.isEmpty) {
          endpoint = instances.get(0).endpoint()
        }
      } catch {
        case _: Exception =>
      }
      if (endpoint == null) {
        Thread.sleep(2000)
        attempts += 1
      }
    }
    require(endpoint != null, s"RDS instance $dbInstanceId not available after 60s")
    val url = s"jdbc:postgresql://${endpoint.address()}:${endpoint.port()}/postgres"
    println(s"RDS JDBC URL: $url")
    url
  }

  private def seedInvestorsTable(): Unit = {
    Class.forName("org.postgresql.Driver")
    val conn = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword)
    try {
      val stmt = conn.createStatement()
      try {
        stmt.execute("""
          CREATE TABLE IF NOT EXISTS public.investors (
            customer_id       SERIAL PRIMARY KEY,
            full_name         VARCHAR(100) NOT NULL,
            annual_income_usd NUMERIC(12,2),
            customer_segment  VARCHAR(50),
            domicile_currency VARCHAR(3),
            join_date         DATE
          )
        """)
        stmt.execute(s"""
          INSERT INTO public.investors (full_name, annual_income_usd, customer_segment, domicile_currency, join_date) VALUES
            ('Alice Johnson', 95000.00, '$segment', 'USD', '2022-03-15'),
            ('Bob Smith',     72000.00, '$segment', 'USD', '2021-07-01'),
            ('Carol Davis',   88000.00, '$segment', 'EUR', '2023-01-10'),
            ('Dave Wilson',   105000.00,'$segment', 'GBP', '2020-11-20'),
            ('Eve Martin',    65000.00, '$segment', 'USD', '2024-02-28')
        """)
      } finally {
        stmt.close()
      }
      println(s"investors table seeded with 5 $segment investors")
    } finally {
      conn.close()
    }
  }

  "ETL" should "read investors from PostgreSQL via JDBC and write pipe-separated CSV to S3" in {
    InvestorToS3Core.run(spark, jdbcUrl, jdbcUser, jdbcPassword, s"s3a://$outputBucket/data/", segment)
  }

  "S3 output" should "contain 5 pipe-separated records with header" in {
    val df = spark.read
      .option("delimiter", "|")
      .option("header", "true")
      .csv(s"s3a://$outputBucket/data/")

    val rows = df.collect()
    rows.length shouldBe 5

    val names = rows.map(_.getAs[String]("full_name")).toSet
    names should contain("Alice Johnson")
    names should contain("Bob Smith")
    names should contain("Carol Davis")
    names should contain("Dave Wilson")
    names should contain("Eve Martin")

    val fields = df.head().schema.fieldNames.toSet
    fields should contain("customer_id")
    fields should contain("full_name")
    fields should contain("annual_income_usd")
    fields should contain("customer_segment")
    fields should contain("domicile_currency")
    fields should contain("join_date")
    fields should contain("segment_status")
  }

  override def afterAll(): Unit = {
    try {
      val objects = s3.listObjectsV2(ListObjectsV2Request.builder().bucket(outputBucket).build()).contents()
      objects.asScala.foreach { obj =>
        s3.deleteObject(DeleteObjectRequest.builder().bucket(outputBucket).key(obj.key()).build())
      }
      s3.deleteBucket(DeleteBucketRequest.builder().bucket(outputBucket).build())
    } catch { case _: Exception => }

    try {
      rds.deleteDBInstance(
        DeleteDbInstanceRequest.builder().dbInstanceIdentifier(dbInstanceId).skipFinalSnapshot(true).build()
      )
    } catch { case _: Exception => }

    _root_.scala.Option(spark).foreach(_.stop())
    _root_.scala.Option(s3).foreach(_.close())
    _root_.scala.Option(rds).foreach(_.close())
  }
}
