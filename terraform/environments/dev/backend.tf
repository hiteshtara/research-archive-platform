terraform {
  backend "s3" {
    bucket       = "kuali-legacy-portal-tfstate-589744711110"
    key          = "research-archive-platform/environments/dev/terraform.tfstate"
    region       = "us-east-1"
    encrypt      = true
    use_lockfile = true
  }
}
