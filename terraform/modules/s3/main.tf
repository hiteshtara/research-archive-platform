locals {
  data_bucket_name = "${var.project_name}-${var.environment}-data-${var.aws_account_id}"
  docs_bucket_name = "${var.project_name}-${var.environment}-documents-${var.aws_account_id}"
}

#
# DATA BUCKET
# Stores CSV, Parquet, JSON, validation reports, and ETL files.
#

resource "aws_s3_bucket" "data" {
  bucket        = local.data_bucket_name
  force_destroy = var.force_destroy

  tags = {
    Name        = "${var.project_name}-${var.environment}-data"
    Purpose     = "Approved data exports and ETL processing"
    DataType    = "StructuredData"
    Environment = var.environment
  }
}

resource "aws_s3_bucket_versioning" "data" {
  bucket = aws_s3_bucket.data.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "data" {
  bucket = aws_s3_bucket.data.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "data" {
  bucket = aws_s3_bucket.data.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "data" {
  bucket = aws_s3_bucket.data.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "data" {
  bucket = aws_s3_bucket.data.id

  rule {
    id     = "archive-old-processed-files"
    status = "Enabled"

    filter {
      prefix = "processed/"
    }

    transition {
      days          = 90
      storage_class = "STANDARD_IA"
    }

    transition {
      days          = 365
      storage_class = "GLACIER_IR"
    }
  }

  depends_on = [
    aws_s3_bucket_versioning.data
  ]
}

resource "aws_s3_object" "data_landing_prefix" {
  bucket  = aws_s3_bucket.data.id
  key     = "landing/"
  content = ""
}

resource "aws_s3_object" "data_processed_prefix" {
  bucket  = aws_s3_bucket.data.id
  key     = "processed/"
  content = ""
}

resource "aws_s3_object" "data_rejected_prefix" {
  bucket  = aws_s3_bucket.data.id
  key     = "rejected/"
  content = ""
}

resource "aws_s3_object" "data_validation_prefix" {
  bucket  = aws_s3_bucket.data.id
  key     = "validation/"
  content = ""
}

#
# DOCUMENTS BUCKET
# Stores PDFs, Word documents, images, and other legacy attachments.
#

resource "aws_s3_bucket" "documents" {
  bucket        = local.docs_bucket_name
  force_destroy = var.force_destroy

  tags = {
    Name        = "${var.project_name}-${var.environment}-documents"
    Purpose     = "Legacy research administration documents"
    DataType    = "Documents"
    Environment = var.environment
  }
}

resource "aws_s3_bucket_versioning" "documents" {
  bucket = aws_s3_bucket.documents.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "documents" {
  bucket = aws_s3_bucket.documents.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "documents" {
  bucket = aws_s3_bucket.documents.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "documents" {
  bucket = aws_s3_bucket.documents.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "documents" {
  bucket = aws_s3_bucket.documents.id

  rule {
    id     = "archive-legacy-documents"
    status = "Enabled"

    filter {}

    transition {
      days          = 180
      storage_class = "STANDARD_IA"
    }

    transition {
      days          = 730
      storage_class = "GLACIER_IR"
    }
  }

  depends_on = [
    aws_s3_bucket_versioning.documents
  ]
}

resource "aws_s3_object" "documents_irb_prefix" {
  bucket  = aws_s3_bucket.documents.id
  key     = "irb/"
  content = ""
}

resource "aws_s3_object" "documents_awards_prefix" {
  bucket  = aws_s3_bucket.documents.id
  key     = "awards/"
  content = ""
}

resource "aws_s3_object" "documents_proposals_prefix" {
  bucket  = aws_s3_bucket.documents.id
  key     = "proposals/"
  content = ""
}

resource "aws_s3_object" "documents_negotiations_prefix" {
  bucket  = aws_s3_bucket.documents.id
  key     = "negotiations/"
  content = ""
}

resource "aws_s3_object" "documents_subawards_prefix" {
  bucket  = aws_s3_bucket.documents.id
  key     = "subawards/"
  content = ""
}
