data "aws_caller_identity" "current" {}

data "aws_iam_policy_document" "glue_assume_role" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["glue.amazonaws.com"]
    }
  }
}

data "aws_iam_policy_document" "glue_job_policy" {
  statement {
    actions = [
      "s3:GetObject",
      "s3:PutObject",
      "s3:DeleteObject",
      "s3:ListBucket",
    ]
    resources = [
      "arn:aws:s3:::${var.artifacts_bucket}",
      "arn:aws:s3:::${var.artifacts_bucket}/*",
      "arn:aws:s3:::${var.output_bucket}",
      "arn:aws:s3:::${var.output_bucket}/*",
    ]
  }

  statement {
    actions = [
      "ec2:CreateNetworkInterface",
      "ec2:DescribeNetworkInterfaces",
      "ec2:DeleteNetworkInterface",
    ]
    resources = ["*"]
  }

  statement {
    actions = [
      "rds-db:connect",
    ]
    resources = ["*"]
  }

  statement {
    actions = [
      "logs:CreateLogGroup",
      "logs:CreateLogStream",
      "logs:PutLogEvents",
      "logs:DescribeLogGroups",
      "logs:DescribeLogStreams",
      "logs:PutRetentionPolicy",
    ]
    resources = ["arn:aws:logs:${var.aws_region}:*:log-group:/aws-glue/${var.project_name}-*:*"]
  }
}

resource "aws_iam_role" "glue_service_role" {
  name               = "${var.project_name}-glue-role"
  assume_role_policy = data.aws_iam_policy_document.glue_assume_role.json
}

resource "aws_iam_role_policy" "glue_job_policy" {
  name   = "${var.project_name}-glue-policy"
  role   = aws_iam_role.glue_service_role.id
  policy = data.aws_iam_policy_document.glue_job_policy.json
}

resource "aws_s3_bucket" "glue_artifacts" {
  bucket = var.artifacts_bucket
  tags   = { Name = var.artifacts_bucket }
}

resource "aws_s3_bucket" "glue_output" {
  bucket = var.output_bucket
  tags   = { Name = var.output_bucket }
}

locals {
  deploy_dir = "${path.module}/../glue-deploy"
  is_aws     = var.glue_trigger_mode == "aws"
}

resource "aws_s3_object" "glue_script" {
  bucket = aws_s3_bucket.glue_artifacts.id
  key    = "scripts/EmpToS3Job.scala"
  source = "${local.deploy_dir}/scripts/EmpToS3Job.scala"
  etag   = filemd5("${local.deploy_dir}/scripts/EmpToS3Job.scala")
}

resource "aws_s3_object" "app_jar" {
  bucket = aws_s3_bucket.glue_artifacts.id
  key    = "jars/glue5-spark-job-assembly-1.0.jar"
  source = "${local.deploy_dir}/jars/glue5-spark-job-assembly-1.0.jar"
  etag   = filemd5("${local.deploy_dir}/jars/glue5-spark-job-assembly-1.0.jar")
}

resource "aws_s3_object" "lambda_file_handler_jar" {
  bucket = aws_s3_bucket.glue_artifacts.id
  key    = "lambdas/investor-file-handler-1.0.jar"
  source = "${path.module}/../lambda-investor-file-handler/target/investor-file-handler-1.0.jar"
  etag   = filemd5("${path.module}/../lambda-investor-file-handler/target/investor-file-handler-1.0.jar")
}

resource "aws_s3_object" "lambda_sal_processor_jar" {
  bucket = aws_s3_bucket.glue_artifacts.id
  key    = "lambdas/investor-sal-processor-1.0.jar"
  source = "${path.module}/../lambda-investor-sal-processor/target/investor-sal-processor-1.0.jar"
  etag   = filemd5("${path.module}/../lambda-investor-sal-processor/target/investor-sal-processor-1.0.jar")
}

# aws_glue_job is only created for real AWS (mode = "aws").
# Floci does not support CreateJob, so skip it for local dev.
resource "aws_glue_job" "emp_to_s3" {
  count = local.is_aws ? 1 : 0

  name     = "${var.project_name}-emp-to-s3"
  role_arn = aws_iam_role.glue_service_role.arn
  glue_version = "5.0"

  command {
    script_location = "s3://${aws_s3_bucket.glue_artifacts.id}/scripts/EmpToS3Job.scala"
  }

  worker_type       = var.glue_worker_type
  number_of_workers = var.glue_workers

  default_arguments = {
    "--JOB_NAME"            = "${var.project_name}-emp-to-s3"
    "--jdbc_url"            = var.rds_jdbc_url
    "--jdbc_user"           = var.rds_jdbc_user
    "--jdbc_password"       = var.rds_jdbc_password
    "--jdbc_query"          = "SELECT emp_id, emp_name, department, salary, hire_date FROM public.emp ORDER BY emp_id"
    "--output_path"         = "s3://${aws_s3_bucket.glue_output.id}/output/"
    "--job-language"        = "scala"
    "--extra-jars"          = "s3://${aws_s3_bucket.glue_artifacts.id}/jars/glue5-spark-job-assembly-1.0.jar"

    "--continuous-log-logGroup"          = "/aws-glue/${var.project_name}-emp-to-s3"
    "--continuous-log-logStreamPrefix"   = "driver"
    "--enable-continuous-cloudwatch-log" = "true"
    "--enable-continuous-log-filter"     = "true"
  }

  tags = { Name = "${var.project_name}-emp-to-s3" }
}

# ---------------------------------------------------------------------------
# CloudWatch Log Groups (real AWS only)
# ---------------------------------------------------------------------------
resource "aws_cloudwatch_log_group" "glue" {
  count = local.is_aws ? 1 : 0
  name              = "/aws-glue/${var.project_name}-emp-to-s3"
  retention_in_days = var.log_retention_days
  tags              = { Name = "${var.project_name}-glue-logs" }
}

resource "aws_cloudwatch_log_group" "file_handler" {
  count = local.is_aws ? 1 : 0
  name              = "/aws/lambda/${var.project_name}-employee-file-handler"
  retention_in_days = var.log_retention_days
  tags              = { Name = "${var.project_name}-file-handler-logs" }
}

resource "aws_cloudwatch_log_group" "sal_processor" {
  count = local.is_aws ? 1 : 0
  name              = "/aws/lambda/${var.project_name}-employee-sal-processor"
  retention_in_days = var.log_retention_days
  tags              = { Name = "${var.project_name}-sal-processor-logs" }
}

# ---------------------------------------------------------------------------
# S3 bucket for CloudWatch Log exports (real AWS only)
# ---------------------------------------------------------------------------
resource "aws_s3_bucket" "log_export" {
  count  = local.is_aws ? 1 : 0
  bucket = var.log_export_bucket
  tags   = { Name = var.log_export_bucket }
}

resource "aws_s3_bucket_lifecycle_configuration" "log_export" {
  count  = local.is_aws ? 1 : 0
  bucket = aws_s3_bucket.log_export[0].id

  rule {
    id     = "expire-old-logs"
    status = "Enabled"
    expiration {
      days = var.log_export_retention_days
    }
  }
}

# IAM policy allowing CloudWatch Logs to write to the S3 export bucket
data "aws_iam_policy_document" "log_export_bucket_policy" {
  count = local.is_aws ? 1 : 0

  statement {
    actions   = ["s3:GetBucketAcl", "s3:PutObject"]
    resources = [
      aws_s3_bucket.log_export[0].arn,
      "${aws_s3_bucket.log_export[0].arn}/*",
    ]
    principals {
      type        = "Service"
      identifiers = ["logs.${var.aws_region}.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
  }
}

resource "aws_s3_bucket_policy" "log_export" {
  count  = local.is_aws ? 1 : 0
  bucket = aws_s3_bucket.log_export[0].id
  policy = data.aws_iam_policy_document.log_export_bucket_policy[0].json
}

# ---------------------------------------------------------------------------
# CloudWatch → S3 log forwarder Lambda (real AWS only)
# ---------------------------------------------------------------------------
data "archive_file" "log_forwarder" {
  count       = local.is_aws ? 1 : 0
  type        = "zip"
  output_path = "${path.module}/../lambda-log-forwarder/target/log-forwarder.zip"
  source {
    content  = <<-PYTHON
import gzip
import json
import os
from datetime import datetime, timezone

import boto3

s3 = boto3.client("s3")
BUCKET = os.environ["LOG_EXPORT_BUCKET"]

def lambda_handler(event, context):
    payload = gzip.decompress(base64_decode(event["awslogs"]["data"]))
    logs = json.loads(payload)

    group = logs["logGroup"]
    stream = logs["logStream"]
    now = datetime.now(timezone.utc)
    prefix = f"{group}/{now:%Y/%m/%d}/{stream}/{context.aws_request_id}"

    lines = "\n".join(
        json.dumps(e) for e in logs["logEvents"]
    )
    s3.put_object(Bucket=BUCKET, Key=f"{prefix}.jsonl", Body=lines)
    return {"statusCode": 200}

def base64_decode(data):
    import base64
    return base64.b64decode(data)
PYTHON
    filename = "index.py"
  }
}

resource "aws_iam_role" "log_forwarder" {
  count = local.is_aws ? 1 : 0
  name  = "${var.project_name}-log-forwarder-role"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume_role.json
}

data "aws_iam_policy_document" "log_forwarder_policy" {
  count = local.is_aws ? 1 : 0
  statement {
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.log_export[0].arn}/*"]
  }
  statement {
    actions   = ["logs:DescribeLogGroups", "logs:DescribeLogStreams"]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "log_forwarder" {
  count  = local.is_aws ? 1 : 0
  name   = "${var.project_name}-log-forwarder-policy"
  role   = aws_iam_role.log_forwarder[0].id
  policy = data.aws_iam_policy_document.log_forwarder_policy[0].json
}

resource "aws_lambda_function" "log_forwarder" {
  count = local.is_aws ? 1 : 0

  function_name = "${var.project_name}-log-forwarder"
  role          = aws_iam_role.log_forwarder[0].arn
  runtime       = "python3.12"
  handler       = "index.lambda_handler"
  filename      = data.archive_file.log_forwarder[0].output_path
  source_code_hash = data.archive_file.log_forwarder[0].output_base64sha256
  memory_size   = 256
  timeout       = 120

  environment {
    variables = {
      LOG_EXPORT_BUCKET = aws_s3_bucket.log_export[0].id
    }
  }

  tags = { Name = "${var.project_name}-log-forwarder" }
}

# Subscription filters: each log group → the forwarder Lambda
resource "aws_cloudwatch_log_subscription_filter" "glue" {
  count           = local.is_aws ? 1 : 0
  name            = "${var.project_name}-glue-to-s3"
  log_group_name  = aws_cloudwatch_log_group.glue[0].name
  filter_pattern  = ""
  destination_arn = aws_lambda_function.log_forwarder[0].arn
  depends_on      = [
    aws_lambda_permission.log_forwarder_glue,
    aws_lambda_permission.log_forwarder_file_handler,
    aws_lambda_permission.log_forwarder_sal_processor,
  ]
}

resource "aws_cloudwatch_log_subscription_filter" "file_handler" {
  count           = local.is_aws ? 1 : 0
  name            = "${var.project_name}-file-handler-to-s3"
  log_group_name  = aws_cloudwatch_log_group.file_handler[0].name
  filter_pattern  = ""
  destination_arn = aws_lambda_function.log_forwarder[0].arn
}

resource "aws_cloudwatch_log_subscription_filter" "sal_processor" {
  count           = local.is_aws ? 1 : 0
  name            = "${var.project_name}-sal-processor-to-s3"
  log_group_name  = aws_cloudwatch_log_group.sal_processor[0].name
  filter_pattern  = ""
  destination_arn = aws_lambda_function.log_forwarder[0].arn
}

resource "aws_lambda_permission" "log_forwarder_glue" {
  count         = local.is_aws ? 1 : 0
  statement_id  = "AllowExecutionFromCloudWatchGlue"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.log_forwarder[0].function_name
  principal     = "logs.${var.aws_region}.amazonaws.com"
  source_arn    = aws_cloudwatch_log_group.glue[0].arn
}

resource "aws_lambda_permission" "log_forwarder_file_handler" {
  count         = local.is_aws ? 1 : 0
  statement_id  = "AllowExecutionFromCloudWatchFileHandler"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.log_forwarder[0].function_name
  principal     = "logs.${var.aws_region}.amazonaws.com"
  source_arn    = aws_cloudwatch_log_group.file_handler[0].arn
}

resource "aws_lambda_permission" "log_forwarder_sal_processor" {
  count         = local.is_aws ? 1 : 0
  statement_id  = "AllowExecutionFromCloudWatchSalProcessor"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.log_forwarder[0].function_name
  principal     = "logs.${var.aws_region}.amazonaws.com"
  source_arn    = aws_cloudwatch_log_group.sal_processor[0].arn
}

# ---------------------------------------------------------------------------
# SSM Parameters (real AWS only — Floci doesn't handle them via TF provider)
# ---------------------------------------------------------------------------
resource "aws_ssm_parameter" "api_endpoint" {
  count = local.is_aws ? 1 : 0
  name  = "/emp/api/endpoint"
  type  = "String"
  value = "http://wiremock:8080"
}

resource "aws_ssm_parameter" "api_check_enabled" {
  count = local.is_aws ? 1 : 0
  name  = "/emp/api/employee-check-enabled"
  type  = "String"
  value = "true"
}

# ---------------------------------------------------------------------------
# SQS queue for emp-processing (real AWS only)
# ---------------------------------------------------------------------------
resource "aws_sqs_queue" "emp_processing" {
  count = local.is_aws ? 1 : 0
  name  = "${var.project_name}-emp-processing"
  tags  = { Name = "${var.project_name}-emp-processing" }
}

resource "aws_ssm_parameter" "sqs_queue_url" {
  count  = local.is_aws ? 1 : 0
  name   = "/emp/sqs/queue-url"
  type   = "String"
  value  = aws_sqs_queue.emp_processing[0].id
}

# ---------------------------------------------------------------------------
# Lambda IAM roles (only for real AWS; Floci does not execute Java Lambdas)
# ---------------------------------------------------------------------------
data "aws_iam_policy_document" "lambda_assume_role" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

data "aws_iam_policy_document" "lambda_file_handler_policy" {
  statement {
    actions   = ["s3:GetObject", "s3:ListBucket"]
    resources = [
      "arn:aws:s3:::${var.output_bucket}",
      "arn:aws:s3:::${var.output_bucket}/*",
    ]
  }
  statement {
    actions   = ["sqs:SendMessage"]
    resources = [local.is_aws ? aws_sqs_queue.emp_processing[0].arn : ""]
  }
  statement {
    actions   = ["ssm:GetParameter"]
    resources = ["arn:aws:ssm:${var.aws_region}:*:parameter/emp/*"]
  }
}

data "aws_iam_policy_document" "lambda_sal_processor_policy" {
  statement {
    actions   = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes"]
    resources = [local.is_aws ? aws_sqs_queue.emp_processing[0].arn : ""]
  }
  statement {
    actions   = ["ssm:GetParameter"]
    resources = ["arn:aws:ssm:${var.aws_region}:*:parameter/emp/*"]
  }
}

resource "aws_iam_role" "lambda_file_handler" {
  count = local.is_aws ? 1 : 0
  name  = "${var.project_name}-file-handler-role"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume_role.json
}

resource "aws_iam_role_policy" "lambda_file_handler" {
  count  = local.is_aws ? 1 : 0
  name   = "${var.project_name}-file-handler-policy"
  role   = aws_iam_role.lambda_file_handler[0].id
  policy = data.aws_iam_policy_document.lambda_file_handler_policy.json
}

resource "aws_iam_role" "lambda_sal_processor" {
  count = local.is_aws ? 1 : 0
  name  = "${var.project_name}-sal-processor-role"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume_role.json
}

resource "aws_iam_role_policy" "lambda_sal_processor" {
  count  = local.is_aws ? 1 : 0
  name   = "${var.project_name}-sal-processor-policy"
  role   = aws_iam_role.lambda_sal_processor[0].id
  policy = data.aws_iam_policy_document.lambda_sal_processor_policy.json
}

# ---------------------------------------------------------------------------
# Lambda functions (only for real AWS)
# ---------------------------------------------------------------------------
resource "aws_lambda_function" "employee_file_handler" {
  count = local.is_aws ? 1 : 0

  function_name = "${var.project_name}-employee-file-handler"
  role          = aws_iam_role.lambda_file_handler[0].arn
  runtime       = "java17"
  handler       = "com.example.S3ToSqsLambda::handleRequest"
  filename      = "${path.module}/../lambda-investor-file-handler/target/investor-file-handler-1.0.jar"
  source_code_hash = try(filebase64sha256("${path.module}/../lambda-investor-file-handler/target/investor-file-handler-1.0.jar"), "")
  memory_size   = 512
  timeout       = 60

  environment {
    variables = {
      AWS_ENDPOINT_URL  = var.aws_endpoint_url
      AWS_DEFAULT_REGION = var.aws_region
    }
  }

  tags = { Name = "${var.project_name}-employee-file-handler" }
}

resource "aws_lambda_function" "employee_sal_processor" {
  count = local.is_aws ? 1 : 0

  function_name = "${var.project_name}-employee-sal-processor"
  role          = aws_iam_role.lambda_sal_processor[0].arn
  runtime       = "java17"
  handler       = "com.example.SqsProcessorLambda::handleRequest"
  filename      = "${path.module}/../lambda-investor-sal-processor/target/investor-sal-processor-1.0.jar"
  source_code_hash = try(filebase64sha256("${path.module}/../lambda-investor-sal-processor/target/investor-sal-processor-1.0.jar"), "")
  memory_size   = 512
  timeout       = 60

  environment {
    variables = {
      AWS_ENDPOINT_URL  = var.aws_endpoint_url
      AWS_DEFAULT_REGION = var.aws_region
    }
  }

  tags = { Name = "${var.project_name}-employee-sal-processor" }
}

# S3 event notification -> Lambda employee-file-handler
resource "aws_s3_bucket_notification" "file_handler_notification" {
  count  = local.is_aws ? 1 : 0
  bucket = aws_s3_bucket.glue_output.id

  lambda_function {
    lambda_function_arn = aws_lambda_function.employee_file_handler[0].arn
    events              = ["s3:ObjectCreated:*"]
    filter_suffix       = ".csv"
  }
}

# SQS event source mapping -> Lambda employee-sal-processor
resource "aws_lambda_event_source_mapping" "sal_processor_trigger" {
  count = local.is_aws ? 1 : 0

  event_source_arn = aws_sqs_queue.emp_processing[0].arn
  function_name    = aws_lambda_function.employee_sal_processor[0].arn
  batch_size       = 10
}

# ---------------------------------------------------------------------------
# Trigger the Glue job after provisioning via the shared shell script
# (scripts/run-glue-job.sh).  Supports two modes:
#   docker  – run spark-submit inside the glue-runner container (local dev)
#   aws     – call aws glue start-job-run via the AWS API
# ---------------------------------------------------------------------------
locals {
  glue_job_name = local.is_aws ? aws_glue_job.emp_to_s3[0].name : "${var.project_name}-emp-to-s3"
  trigger_cmd   = "${path.module}/../scripts/run-glue-job.sh --mode aws --job-name ${local.glue_job_name} --wait"
}

resource "terraform_data" "run_glue_job" {
  count = local.is_aws ? 1 : 0

  triggers_replace = [
    local.glue_job_name,
    filemd5("${path.module}/../scripts/run-glue-job.sh"),
    var.glue_trigger_mode,
  ]

  provisioner "local-exec" {
    command = local.trigger_cmd
    environment = {
      AWS_ACCESS_KEY_ID     = var.aws_access_key_id
      AWS_SECRET_ACCESS_KEY = var.aws_secret_access_key
      AWS_ENDPOINT_URL      = var.aws_endpoint_url
      AWS_REGION            = var.aws_region
    }
  }
}
