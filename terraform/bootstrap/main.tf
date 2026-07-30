# Bootstrap: creates the S3 bucket that every environment's backend.tf
# uses for remote state. This config starts on LOCAL state (there is no
# backend block below) - it solves the chicken-and-egg problem of "the
# remote backend needs a bucket that Terraform itself would normally
# track in that same remote backend."
#
# This is NOT "run once and forget": after the first apply succeeds,
# migrate this config's own state into the bucket it just created (see
# backend.tf.example and terraform/README.md's "Backend bootstrap" section
# for the exact steps and the recovery procedure if state is ever lost).
# Never destroy or recreate the resources below outside that documented
# recovery procedure - every other environment's remote state depends on
# this bucket continuing to exist untouched.

terraform {
  required_version = ">= 1.8.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

provider "aws" {
  region              = var.aws_region
  allowed_account_ids = [var.expected_account_id]
}

resource "aws_s3_bucket" "state" {
  bucket = var.state_bucket_name

  tags = {
    Name    = var.state_bucket_name
    Purpose = "Terraform remote state"
  }

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_s3_bucket_versioning" "state" {
  bucket = aws_s3_bucket.state.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "state" {
  bucket = aws_s3_bucket.state.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "state" {
  bucket = aws_s3_bucket.state.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "state" {
  bucket = aws_s3_bucket.state.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}
