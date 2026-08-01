output "cluster_name" {
  value = aws_ecs_cluster.this.name
}

output "task_definition_arn" {
  value = aws_ecs_task_definition.loader.arn
}

output "loader_security_group_id" {
  value = aws_security_group.loader.id
}

output "loader_task_role_arn" {
  description = "ARN of the task role application code inside the loader container runs as (Secrets Manager/S3/STS calls are authorized here, not via the execution role)."
  value       = aws_iam_role.task.arn
}
