output "state_bucket_name" {
  description = "Name of the created state bucket. Use this in each environment's backend.hcl."
  value       = aws_s3_bucket.state.bucket
}
