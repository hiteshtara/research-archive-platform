output "cluster_name" {
  value = aws_ecs_cluster.this.name
}

output "task_definition_arn" {
  value = aws_ecs_task_definition.loader.arn
}

output "loader_security_group_id" {
  value = aws_security_group.loader.id
}
