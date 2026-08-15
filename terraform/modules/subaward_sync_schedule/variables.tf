variable "project_name" {
  description = "Project name used in resource names."
  type        = string
}

variable "environment" {
  description = "Environment name."
  type        = string
}

variable "aws_region" {
  description = "AWS region."
  type        = string
}

variable "account_id" {
  description = "AWS account ID (used to build a task-definition-family-scoped ecs:RunTask resource ARN)."
  type        = string
}

variable "ecs_cluster_arn" {
  description = "ARN of the existing ETL ECS cluster the nightly sync runs on. This module never creates a cluster."
  type        = string
}

variable "task_definition_family" {
  description = "The loader task definition's family name (no :revision) - passed to RunTask as-is so the scheduled task always launches the latest ACTIVE revision, matching this project's existing loader release model (new revisions are registered directly by scripts/run-*-loader.sh/ops/deploy-*.sh, outside Terraform)."
  type        = string
}

variable "container_name" {
  description = "Name of the container in the loader task definition to apply the --sync-all command override to."
  type        = string
  default     = "loader"
}

variable "private_subnet_ids" {
  description = "Existing private subnets the scheduled task runs in (no public IP)."
  type        = list(string)
}

variable "security_group_id" {
  description = "Existing loader security group ID the scheduled task runs with."
  type        = string
}

variable "execution_role_arn" {
  description = "ARN of the loader task definition's execution role - the scheduler's IAM role needs iam:PassRole for this."
  type        = string
}

variable "task_role_arn" {
  description = "ARN of the loader task definition's task role - the scheduler's IAM role needs iam:PassRole for this."
  type        = string
}

variable "log_group_name" {
  description = "Existing loader CloudWatch log group name, used for the failure/staleness metric filters below."
  type        = string
}

variable "schedule_expression" {
  description = "EventBridge Scheduler cron/rate expression for the nightly sync."
  type        = string
  default     = "cron(0 2 * * ? *)"
}

variable "schedule_timezone" {
  description = "IANA timezone the schedule_expression is evaluated in."
  type        = string
  default     = "America/New_York"
}
