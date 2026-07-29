variable "project_name" {
  description = "Project name used in resource names."
  type        = string
}

variable "environment" {
  description = "Environment name."
  type        = string
}

variable "aws_region" {
  description = "AWS region."
  type        = string
}

variable "vpc_id" {
  description = "VPC ID."
  type        = string
}

variable "private_subnet_ids" {
  description = "Private subnets used by the ETL loader task."
  type        = list(string)
}

variable "loader_image" {
  description = "Complete loader container image reference, including tag."
  type        = string
}

variable "data_bucket_arn" {
  description = "ARN of the S3 bucket the loader reads/writes ETL exports from."
  type        = string
}

variable "data_bucket_name" {
  description = "Name of the S3 bucket the loader reads/writes ETL exports from."
  type        = string
}

variable "database_secret_arn" {
  description = "Secrets Manager ARN containing PostgreSQL connection details."
  type        = string
}

variable "database_security_group_id" {
  description = "RDS security group ID."
  type        = string
}

variable "log_retention_days" {
  description = "CloudWatch Logs retention for the loader log group."
  type        = number
  default     = 14
}
