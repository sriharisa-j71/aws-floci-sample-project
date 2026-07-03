variable "aws_region" {
  description = "AWS region for resources"
  type        = string
  default     = "us-east-1"
}

variable "project_name" {
  description = "Project name for resource tagging"
  type        = string
  default     = "emp-glue-job"
}

variable "artifacts_bucket" {
  description = "S3 bucket name for Glue job scripts and JAR dependencies"
  type        = string
}

variable "output_bucket" {
  description = "S3 bucket name for Glue job output"
  type        = string
}

variable "rds_jdbc_url" {
  description = "JDBC URL of the source RDS PostgreSQL database"
  type        = string
  sensitive   = true
}

variable "rds_jdbc_user" {
  description = "JDBC username for the source RDS database"
  type        = string
  sensitive   = true
}

variable "rds_jdbc_password" {
  description = "JDBC password for the source RDS database"
  type        = string
  sensitive   = true
}

variable "glue_worker_type" {
  description = "Glue worker type (G.1X, G.2X, etc.)"
  type        = string
  default     = "G.1X"
}

variable "glue_workers" {
  description = "Number of Glue workers"
  type        = number
  default     = 2
}

variable "aws_access_key_id" {
  description = "AWS access key ID for local-exec script (default: Floci test credentials)"
  type        = string
  default     = "test"
  sensitive   = true
}

variable "aws_secret_access_key" {
  description = "AWS secret access key for local-exec script (default: Floci test credentials)"
  type        = string
  default     = "test"
  sensitive   = true
}

variable "aws_endpoint_url" {
  description = "AWS endpoint URL for local-exec script (empty = real AWS, set for Floci/LocalStack)"
  type        = string
  default     = ""
}

variable "glue_trigger_mode" {
  description = "How to trigger the Glue job after provisioning: 'docker' (run via glue-runner container) or 'aws' (call AWS API)"
  type        = string
  default     = "docker"
}
