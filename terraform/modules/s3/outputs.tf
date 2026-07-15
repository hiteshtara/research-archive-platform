output "data_bucket_name" {
  description = "Bucket used for structured exports and ETL processing."
  value       = aws_s3_bucket.data.bucket
}

output "data_bucket_arn" {
  description = "ARN of the structured data bucket."
  value       = aws_s3_bucket.data.arn
}

output "data_landing_prefix" {
  description = "Landing location for locally generated exports."
  value       = "s3://${aws_s3_bucket.data.bucket}/landing/"
}

output "documents_bucket_name" {
  description = "Bucket used for legacy documents and attachments."
  value       = aws_s3_bucket.documents.bucket
}

output "documents_bucket_arn" {
  description = "ARN of the legacy documents bucket."
  value       = aws_s3_bucket.documents.arn
}
