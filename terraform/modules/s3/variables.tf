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
  description = "Allow Terraform to delete bucket contents if the bucket itself is ever destroyed. Note the bucket resources also carry prevent_destroy = true, so a normal 'terraform destroy' will refuse regardless; this only matters if that lifecycle block is deliberately removed first."
  type        = bool
  default     = false
}
