output "database_endpoint" {
  description = "PostgreSQL endpoint."
  value       = aws_db_instance.this.address
}

output "database_port" {
  description = "PostgreSQL port."
  value       = aws_db_instance.this.port
}

output "database_name" {
  description = "PostgreSQL database name."
  value       = var.database_name
}

output "database_security_group_id" {
  description = "Database security group ID."
  value       = aws_security_group.database.id
}

output "database_secret_arn" {
  description = "Secrets Manager ARN containing database connection details."
  value       = aws_secretsmanager_secret.database.arn
}

output "database_identifier" {
  description = "RDS database identifier."
  value       = aws_db_instance.this.identifier
}
