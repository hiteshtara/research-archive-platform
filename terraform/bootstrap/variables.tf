variable "expected_account_id" {
  description = "The 12-digit AWS account ID to create the state bucket in."
  type        = string

  validation {
    condition     = can(regex("^[0-9]{12}$", var.expected_account_id))
    error_message = "expected_account_id must be a 12-digit AWS account ID."
  }
}

variable "aws_region" {
  description = "AWS region for the state bucket."
  type        = string
  default     = "us-east-1"
}

variable "state_bucket_name" {
  description = "Globally-unique name for the Terraform state bucket, e.g. \"research-archive-platform-tfstate-<account-id>\". Must not collide with any bucket name in any AWS account."
  type        = string
}
