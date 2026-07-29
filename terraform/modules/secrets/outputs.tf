output "openai_secret_arn" {
  description = "ARN of the OpenAI API key secret container (value must be populated out-of-band)."
  value       = aws_secretsmanager_secret.openai.arn
}

output "openai_secret_name" {
  description = "Name of the OpenAI API key secret, for use with 'aws secretsmanager put-secret-value'."
  value       = aws_secretsmanager_secret.openai.name
}
