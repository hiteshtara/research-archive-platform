output "account_id" {
  description = "AWS account ID Terraform is running against (should equal expected_account_id)."
  value       = data.aws_caller_identity.current.account_id
}

output "project_name" {
  value = var.project_name
}

output "environment" {
  value = var.environment
}

output "vpc_id" {
  value = module.vpc.vpc_id
}

output "public_subnet_ids" {
  value = module.vpc.public_subnet_ids
}

output "private_subnet_ids" {
  value = module.vpc.private_subnet_ids
}

output "database_endpoint" {
  value = module.rds.database_endpoint
}

output "database_port" {
  value = module.rds.database_port
}

output "database_name" {
  value = module.rds.database_name
}

output "database_secret_arn" {
  value = module.rds.database_secret_arn
}

output "database_security_group_id" {
  value = module.rds.database_security_group_id
}

output "data_bucket_name" {
  description = "S3 bucket receiving approved local data exports."
  value       = module.archive_s3.data_bucket_name
}

output "data_landing_location" {
  description = "Landing location for local ETL exports."
  value       = module.archive_s3.data_landing_prefix
}

output "documents_bucket_name" {
  description = "S3 bucket storing legacy documents."
  value       = module.archive_s3.documents_bucket_name
}

output "openai_secret_name" {
  description = "Secrets Manager secret name to populate with 'aws secretsmanager put-secret-value' (see README)."
  value       = module.openai_secret.openai_secret_name
}

output "loader_ecr_repository_url" {
  value = module.loader_ecr.repository_url
}

output "loader_ecs_cluster_name" {
  value = module.loader_ecs.cluster_name
}

output "loader_task_definition_arn" {
  value = module.loader_ecs.task_definition_arn
}

output "loader_security_group_id" {
  value = module.loader_ecs.loader_security_group_id
}

output "api_ecr_repository_url" {
  value = module.api_ecr.repository_url
}

output "api_url" {
  description = "Public base URL of the Research Archive API."
  value       = module.api_service.alb_url
}

output "api_alb_dns_name" {
  value = module.api_service.alb_dns_name
}

output "api_ecs_cluster_name" {
  value = module.api_service.cluster_name
}

output "api_ecs_service_name" {
  value = module.api_service.service_name
}

output "api_task_definition_arn" {
  value = module.api_service.task_definition_arn
}

output "cognito_user_pool_id" {
  description = "Cognito User Pool ID (only when manage_cognito = true; null otherwise, since the pool is externally managed)."
  value       = var.manage_cognito ? module.cognito[0].user_pool_id : null
}

output "cognito_issuer_uri" {
  description = "OIDC issuer URI in effect (created pool if manage_cognito = true, otherwise the supplied existing pool)."
  value       = local.cognito_issuer_uri
}

output "cognito_client_id" {
  description = "Cognito app client ID in effect."
  value       = local.cognito_client_id
}

output "ui_url" {
  description = "Public URL of the deployed UI (only when manage_amplify = true; null otherwise, since the UI is externally hosted)."
  value       = var.manage_amplify ? module.amplify[0].branch_url : null
}
