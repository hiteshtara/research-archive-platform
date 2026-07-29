# See environments/dev/backend.tf for the full explanation. Supply the
# bucket at init time:
#
#   terraform init -backend-config=backend.hcl

terraform {
  backend "s3" {
    key          = "research-archive-platform/environments/prod/terraform.tfstate"
    region       = "us-east-1"
    encrypt      = true
    use_lockfile = true
  }
}
