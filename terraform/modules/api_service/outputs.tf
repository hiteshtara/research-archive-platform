output "alb_dns_name" {
  description = "Public DNS name of the API load balancer."
  value       = aws_lb.api.dns_name
}

output "cluster_name" {
  description = "API ECS cluster name."
  value       = aws_ecs_cluster.api.name
}

output "service_name" {
  description = "API ECS service name."
  value       = aws_ecs_service.api.name
}

output "task_definition_arn" {
  description = "API task definition ARN."
  value       = aws_ecs_task_definition.api.arn
}

output "api_security_group_id" {
  description = "API ECS task security group."
  value       = aws_security_group.api.id
}
