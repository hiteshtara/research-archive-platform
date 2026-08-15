output "schedule_arn" {
  value = aws_scheduler_schedule.subaward_nightly.arn
}

output "schedule_name" {
  value = aws_scheduler_schedule.subaward_nightly.name
}

output "scheduler_role_arn" {
  value = aws_iam_role.scheduler.arn
}

output "sns_topic_arn" {
  description = "Subscribe an operator endpoint (email/Slack/etc.) to this topic out of band - see docs/runbooks/SUBAWARD_NIGHTLY_SYNC.md."
  value       = aws_sns_topic.subaward_sync_alerts.arn
}

output "target_task_definition_arn" {
  description = "The specific task-definition revision ARN this schedule was pointed at as of the last terraform apply (resolved from the family's latest ACTIVE revision at that time - see main.tf's data.aws_ecs_task_definition.loader_latest)."
  value       = data.aws_ecs_task_definition.loader_latest.arn
}
