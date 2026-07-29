provider "aws" {
  region = var.aws_region

  # Hard safeguard: refuse to plan/apply if the active credentials resolve
  # to any account other than the one this environment is pinned to.
  allowed_account_ids = [var.expected_account_id]

  default_tags {
    tags = {
      Project     = var.project_name
      Environment = var.environment
      ManagedBy   = "Terraform"
    }
  }
}
