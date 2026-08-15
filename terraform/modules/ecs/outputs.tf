output "cluster_name" {
  value = aws_ecs_cluster.this.name
}

output "cluster_arn" {
  value = aws_ecs_cluster.this.arn
}

output "task_definition_arn" {
  value = aws_ecs_task_definition.loader.arn
}

output "task_definition_family" {
  description = "The loader task family name (no revision) - RunTask/EventBridge Scheduler callers should target this, not task_definition_arn's pinned revision, so a scheduled invocation always picks up whatever image was most recently deployed via scripts/run-*-loader.sh/ops/deploy-*.sh (which register new revisions directly against ECS, outside Terraform)."
  value       = aws_ecs_task_definition.loader.family
}

output "execution_role_arn" {
  description = "ARN of the loader task's execution role (image pull / log delivery) - needed alongside loader_task_role_arn wherever something else (e.g. an EventBridge Scheduler role) must be granted iam:PassRole to launch this task definition."
  value       = aws_iam_role.execution.arn
}

output "loader_security_group_id" {
  value = aws_security_group.loader.id
}

output "loader_task_role_arn" {
  description = "ARN of the task role application code inside the loader container runs as (Secrets Manager/S3/STS calls are authorized here, not via the execution role)."
  value       = aws_iam_role.task.arn
}
