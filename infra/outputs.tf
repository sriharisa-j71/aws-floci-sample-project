output "glue_job_name" {
  description = "Name of the Glue job"
  value       = local.is_aws ? aws_glue_job.emp_to_s3[0].name : null
}

output "glue_role_arn" {
  description = "ARN of the Glue service role"
  value       = aws_iam_role.glue_service_role.arn
}

output "output_bucket" {
  description = "S3 bucket for Glue job output"
  value       = aws_s3_bucket.glue_output.id
}

output "artifacts_bucket" {
  description = "S3 bucket containing Glue job scripts and JARs"
  value       = aws_s3_bucket.glue_artifacts.id
}

output "jdbc_query" {
  description = "SQL query executed by the Glue job against the emp table"
  value       = "SELECT emp_id, emp_name, department, salary, hire_date FROM public.emp ORDER BY emp_id"
}

output "spark_submit_command" {
  description = "Local spark-submit command for testing in the Glue container"
  value       = <<-EOT
    spark-submit --class com.example.Main --master local[*] \
      --conf spark.hadoop.fs.s3a.endpoint=http://localhost:4566 \
      --conf spark.hadoop.fs.s3a.path.style.access=true \
      --conf spark.hadoop.fs.s3a.connection.ssl.enabled=false \
      --conf spark.hadoop.fs.s3a.aws.credentials.provider=org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider \
      --conf spark.hadoop.fs.s3a.access.key=test \
      --conf spark.hadoop.fs.s3a.secret.key=test \
      --jars /usr/share/aws/glue-pds/jars/postgresql-42.7.3.jar \
      /opt/job.jar \
      "${var.rds_jdbc_url}" "${var.rds_jdbc_user}" "${var.rds_jdbc_password}" \
      "SELECT emp_id, emp_name, department, salary, hire_date FROM public.emp ORDER BY emp_id" \
      "s3a://${var.output_bucket}/output/"
  EOT
  sensitive   = true
}
