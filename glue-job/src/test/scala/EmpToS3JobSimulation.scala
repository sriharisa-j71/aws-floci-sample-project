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
import software.amazon.awssdk.services.glue.GlueClient
import software.amazon.awssdk.services.glue.model.{Column, CreateDatabaseRequest, CreateTableRequest, DatabaseInput, DeleteDatabaseRequest, DeleteTableRequest, GetTableRequest, SerDeInfo, StorageDescriptor, TableInput}
import software.amazon.awssdk.services.rds.RdsClient
import software.amazon.awssdk.services.rds.model.{CreateDbInstanceRequest, DeleteDbInstanceRequest, DescribeDbInstancesRequest, Endpoint}

import java.net.URI
import java.sql.DriverManager
import scala.jdk.CollectionConverters._
import scala.util.Using

class EmpToS3JobSimulation extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val endpoint = URI.create("http://localhost:4566")
  private val region = Region.US_EAST_1
  private val creds = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))

  private var spark: SparkSession = _
  private var s3: S3Client = _
  private var glue: GlueClient = _
  private var rds: RdsClient = _

  private val inputBucket  = "emp-input"
  private val outputBucket = "emp-output"
  private val databaseName = "analytics"
  private val tableName    = "emp"
  private val dbInstanceId = "emp-db"

  private val jdbcUser     = "admin"
  private val jdbcPassword = "secret123"
  private val jdbcQuery    = "SELECT emp_id, emp_name, department, salary, hire_date FROM public.emp ORDER BY emp_id"

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

    glue = GlueClient.builder()
      .region(region).credentialsProvider(creds)
      .endpointOverride(endpoint).build()

    rds = RdsClient.builder()
      .region(region).credentialsProvider(creds)
      .endpointOverride(endpoint).build()

    setupFlociResources()
  }

  private def setupFlociResources(): Unit = {
    createS3Bucket(inputBucket)
    createS3Bucket(outputBucket)
    createRdsInstance()
    jdbcUrl = waitForRdsAndGetJdbcUrl()
    seedEmpTable()
    createGlueDatabase()
    createGlueTable()
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

  private def seedEmpTable(): Unit = {
    Class.forName("org.postgresql.Driver")
    Using.resource(DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword)) { conn =>
      Using.resource(conn.createStatement()) { stmt =>
        stmt.execute("""
          CREATE TABLE IF NOT EXISTS public.emp (
            emp_id     SERIAL PRIMARY KEY,
            emp_name   VARCHAR(100) NOT NULL,
            department VARCHAR(100),
            salary     NUMERIC(10,2),
            hire_date  DATE
          )
        """)
        stmt.execute("""
          INSERT INTO public.emp (emp_name, department, salary, hire_date) VALUES
            ('Alice Johnson', 'Engineering', 95000.00, '2022-03-15'),
            ('Bob Smith',     'Marketing',   72000.00, '2021-07-01'),
            ('Carol Davis',   'Finance',     88000.00, '2023-01-10'),
            ('Dave Wilson',   'Engineering', 105000.00,'2020-11-20'),
            ('Eve Martin',    'HR',          65000.00, '2024-02-28')
        """)
      }
      println("emp table created and seeded with 5 rows")
    }
  }

  private def createGlueDatabase(): Unit = {
    try {
      glue.createDatabase(
        CreateDatabaseRequest.builder()
          .databaseInput(
            DatabaseInput.builder().name(databaseName).build()
          )
          .build()
      )
      println(s"Glue database created: $databaseName")
    } catch {
      case e: Exception => println(s"Glue database may already exist: ${e.getMessage}")
    }
  }

  private def createGlueTable(): Unit = {
    try {
      glue.createTable(
        CreateTableRequest.builder()
          .databaseName(databaseName)
          .tableInput(
            TableInput.builder()
              .name(tableName)
              .storageDescriptor(
                StorageDescriptor.builder()
                  .location(s"s3://$inputBucket/data/")
                  .inputFormat("org.apache.hadoop.hive.ql.io.parquet.MapredParquetInputFormat")
                  .outputFormat("org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat")
                  .serdeInfo(
                    SerDeInfo.builder()
                      .serializationLibrary("org.apache.hadoop.hive.ql.io.parquet.serde.ParquetHiveSerDe")
                      .build()
                  )
                  .columns(
                    Column.builder().name("emp_id").`type`("int").build(),
                    Column.builder().name("emp_name").`type`("string").build(),
                    Column.builder().name("department").`type`("string").build(),
                    Column.builder().name("salary").`type`("double").build(),
                    Column.builder().name("hire_date").`type`("date").build()
                  )
                  .build()
              )
              .build()
          )
          .build()
      )
      println(s"Glue table created: $databaseName.$tableName")
    } catch {
      case e: Exception => println(s"Glue table may already exist: ${e.getMessage}")
    }
  }

  "EmpToS3 ETL" should "read from FLOCI PostgreSQL via JDBC and write pipe-separated CSV to FLOCI S3" in {
    InvestorToS3Core.run(spark, jdbcUrl, jdbcUser, jdbcPassword, jdbcQuery, s"s3a://$outputBucket/data/")
    println("Written via InvestorToS3Core.run()")
  }

  "S3 output" should "be pipe-separated with header and contain the expected 5 records" in {
    val outputDf = spark.read
      .option("delimiter", "|")
      .option("header", "true")
      .csv(s"s3a://$outputBucket/data/")

    val rows = outputDf.collect()
    rows.length shouldBe 5

    val names = rows.map(_.getAs[String]("emp_name")).toSet
    names should contain("Alice Johnson")
    names should contain("Bob Smith")
    names should contain("Carol Davis")
    names should contain("Dave Wilson")
    names should contain("Eve Martin")

    val line = spark.read
      .option("delimiter", "|")
      .option("header", "true")
      .csv(s"s3a://$outputBucket/data/")
      .toDF()
      .head()
    line.schema.fieldNames should contain("emp_id")
    line.schema.fieldNames should contain("emp_name")
    line.schema.fieldNames should contain("department")
    line.schema.fieldNames should contain("salary")
    line.schema.fieldNames should contain("hire_date")

    println("Verified 5 pipe-separated employee records with header in S3 output")
  }

  "Glue Data Catalog" should "contain the emp table registered via FLOCI" in {
    val resp = glue.getTable(
      GetTableRequest.builder()
        .databaseName(databaseName)
        .name(tableName)
        .build()
    )

    val table = resp.table()
    table.name() shouldBe tableName
    table.storageDescriptor().location() should include(inputBucket)

    val colNames = table.storageDescriptor().columns().asScala.map(_.name()).toSet
    colNames should contain("emp_id")
    colNames should contain("emp_name")
    colNames should contain("department")
    colNames should contain("salary")
    colNames should contain("hire_date")

    println(s"Glue table $databaseName.$tableName verified (columns: ${colNames.mkString(", ")})")
  }

  override def afterAll(): Unit = {
    try {
      val listReq = ListObjectsV2Request.builder().bucket(outputBucket).build()
      val objects = s3.listObjectsV2(listReq).contents()
      objects.asScala.foreach { obj =>
        s3.deleteObject(DeleteObjectRequest.builder()
          .bucket(outputBucket).key(obj.key()).build())
      }

      try {
        rds.deleteDBInstance(
          DeleteDbInstanceRequest.builder()
            .dbInstanceIdentifier(dbInstanceId)
            .skipFinalSnapshot(true)
            .build()
        )
      } catch { case _: Exception => }

      try {
        glue.deleteTable(
          DeleteTableRequest.builder()
            .databaseName(databaseName).name(tableName).build()
        )
        glue.deleteDatabase(
          DeleteDatabaseRequest.builder().name(databaseName).build()
        )
      } catch { case _: Exception => }

      s3.deleteBucket(DeleteBucketRequest.builder().bucket(outputBucket).build())
      s3.deleteBucket(DeleteBucketRequest.builder().bucket(inputBucket).build())
    } catch { case _: Exception => }

    _root_.scala.Option(spark).foreach(_.stop())
    _root_.scala.Option(s3).foreach(_.close())
    _root_.scala.Option(glue).foreach(_.close())
    _root_.scala.Option(rds).foreach(_.close())
  }
}
