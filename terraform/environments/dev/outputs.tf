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
  description = "Secrets Manager secret name to populate with 'aws secretsmanager put-secret-value' (see README). Null when enable_openai_secret = false."
  value       = var.enable_openai_secret ? module.openai_secret[0].openai_secret_name : null
}

output "oracle_secret_arn" {
  description = "ARN of the Oracle (KCOEUS) credentials secret for the Award Attachment loader. Terraform creates only the empty container - populate the value out-of-band (see docs/AWARD_ATTACHMENT_ECS_EXECUTION.md)."
  value       = aws_secretsmanager_secret.oracle.arn
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

output "loader_task_role_arn" {
  description = "ARN of the task role application code inside the loader container runs as (Secrets Manager/S3/STS calls are authorized here, not via the execution role)."
  value       = module.loader_ecs.loader_task_role_arn
}

output "loader_security_group_id" {
  value = module.loader_ecs.loader_security_group_id
}

output "api_ecr_repository_url" {
  value = module.api_ecr.repository_url
}

output "api_url" {
  description = "Public base URL of the Research Archive API - uses api_domain_name (HTTPS) when configured, otherwise the ALB's own DNS name."
  value       = local.api_url
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
  description = "Cognito User Pool ID in effect (created pool if manage_cognito = true, otherwise the supplied existing pool)."
  value       = local.cognito_user_pool_id
}

output "cognito_issuer_uri" {
  description = "OIDC issuer URI in effect (created pool if manage_cognito = true, otherwise the supplied existing pool)."
  value       = local.cognito_issuer_uri
}

output "cognito_client_id" {
  description = "Cognito app client ID in effect."
  value       = local.cognito_client_id
}

output "cognito_hosted_ui_domain" {
  description = "Cognito Hosted UI domain in effect (created pool if manage_cognito = true, otherwise the supplied existing pool). Null if no Hosted UI domain is configured."
  value       = local.cognito_hosted_ui_domain
}

output "ui_redirect_url" {
  description = "Value baked into the UI build as VITE_COGNITO_REDIRECT_URL - must also be present in cognito_callback_urls."
  value       = var.manage_amplify ? local.ui_redirect_url : null
}

output "ui_logout_url" {
  description = "Value baked into the UI build as VITE_COGNITO_LOGOUT_URL - must also be present in cognito_logout_urls."
  value       = var.manage_amplify ? local.ui_logout_url : null
}

output "ui_url" {
  description = "Public URL of the deployed UI (only when manage_amplify = true; null otherwise, since the UI is externally hosted)."
  value       = var.manage_amplify ? module.amplify[0].branch_url : null
}

output "amplify_app_id" {
  description = "Amplify app ID (only when manage_amplify = true; null otherwise). Needed for aws amplify CLI commands - never hardcode this, it's assigned by AWS at creation time and is account-specific."
  value       = var.manage_amplify ? module.amplify[0].app_id : null
}
