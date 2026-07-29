# Terraform backend blocks cannot reference variables, so the account-
# specific bucket name is intentionally NOT set here. Supply it at init
# time instead:
#
#   terraform init -backend-config=backend.hcl
#
# where backend.hcl (gitignored - see terraform/.gitignore) contains:
#
#   bucket = "<your-state-bucket-name>"
#
# See terraform/bootstrap/ for how to create that bucket, and
# terraform/README.md for the full workflow.

terraform {
  backend "s3" {
    key          = "research-archive-platform/environments/dev/terraform.tfstate"
    region       = "us-east-1"
    encrypt      = true
    use_lockfile = true
  }
}
