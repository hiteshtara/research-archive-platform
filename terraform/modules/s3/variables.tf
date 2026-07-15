variable "project_name" {
  description = "Project name used in bucket naming."
  type        = string
}

variable "environment" {
  description = "Environment name."
  type        = string
}

variable "aws_account_id" {
  description = "AWS account ID used to make bucket names globally unique."
  type        = string
}

variable "force_destroy" {
  description = "Allow Terraform to delete bucket contents during development teardown."
  type        = bool
  default     = true
}
