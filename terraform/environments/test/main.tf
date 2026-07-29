data "aws_caller_identity" "current" {}

# Cross-variable guards Terraform's per-variable validation blocks can't
# express on their own (a variable's validation can only see itself).
resource "terraform_data" "config_guard" {
  lifecycle {
    precondition {
      condition     = var.manage_cognito || (var.cognito_issuer_uri != null && var.cognito_client_id != null)
      error_message = "manage_cognito is false, so cognito_issuer_uri and cognito_client_id must both be set to an existing user pool's values."
    }

    precondition {
      condition     = !var.manage_amplify || (var.amplify_repository_url != null && var.amplify_github_access_token != null)
      error_message = "manage_amplify is true, so amplify_repository_url and amplify_github_access_token must both be set."
    }

    precondition {
      condition     = !var.use_private_subnets_for_api || var.enable_nat_gateway
      error_message = "use_private_subnets_for_api requires enable_nat_gateway = true, or the API will have no route to the public internet (needed for the live OpenAI provider)."
    }
  }
}

module "vpc" {
  source = "../../modules/vpc"

  project_name = var.project_name
  environment  = var.environment
  aws_region   = var.aws_region

  vpc_cidr = var.vpc_cidr

  public_subnet_cidrs  = var.public_subnet_cidrs
  private_subnet_cidrs = var.private_subnet_cidrs
  availability_zones   = var.availability_zones

  enable_nat_gateway = var.enable_nat_gateway
}

module "rds" {
  source = "../../modules/rds"

  project_name = var.project_name
  environment  = var.environment

  vpc_id              = module.vpc.vpc_id
  private_subnet_ids  = module.vpc.private_subnet_ids
  allowed_cidr_blocks = var.database_allowed_cidrs

  database_name   = var.database_name
  master_username = var.database_master_username
  instance_class  = var.database_instance_class

  allocated_storage     = var.database_allocated_storage
  max_allocated_storage = var.database_max_allocated_storage

  backup_retention_days = var.database_backup_retention_days
  deletion_protection   = var.database_deletion_protection
  skip_final_snapshot   = var.database_skip_final_snapshot
}

module "archive_s3" {
  source = "../../modules/s3"

  project_name   = var.project_name
  environment    = var.environment
  aws_account_id = data.aws_caller_identity.current.account_id
}

module "openai_secret" {
  source = "../../modules/secrets"

  project_name = var.project_name
  environment  = var.environment

  recovery_window_in_days = var.openai_secret_recovery_window_days
}

module "loader_ecr" {
  source = "../../modules/ecr"

  project_name = var.project_name
  environment  = var.environment

  force_delete         = var.ecr_force_delete
  image_tag_mutability = var.ecr_image_tag_mutability
}

module "loader_ecs" {
  source = "../../modules/ecs"

  project_name = var.project_name
  environment  = var.environment
  aws_region   = var.aws_region

  vpc_id             = module.vpc.vpc_id
  private_subnet_ids = module.vpc.private_subnet_ids
  loader_image       = "${module.loader_ecr.repository_url}:${var.loader_image_tag}"

  data_bucket_arn  = module.archive_s3.data_bucket_arn
  data_bucket_name = module.archive_s3.data_bucket_name

  database_secret_arn        = module.rds.database_secret_arn
  database_security_group_id = module.rds.database_security_group_id

  log_retention_days = var.loader_log_retention_days
}

module "api_ecr" {
  source = "../../modules/api_ecr"

  project_name = var.project_name
  environment  = var.environment

  force_delete         = var.ecr_force_delete
  image_tag_mutability = var.ecr_image_tag_mutability
}

module "cognito" {
  count  = var.manage_cognito ? 1 : 0
  source = "../../modules/cognito"

  project_name = var.project_name
  environment  = var.environment
  aws_region   = var.aws_region

  callback_urls           = var.cognito_callback_urls
  logout_urls             = var.cognito_logout_urls
  hosted_ui_domain_prefix = var.cognito_hosted_ui_domain_prefix
}

locals {
  # When manage_cognito is true, use the pool this configuration just
  # created; otherwise fall back to the existing pool's IDs supplied as
  # variables.
  cognito_issuer_uri = var.manage_cognito ? module.cognito[0].issuer_uri : var.cognito_issuer_uri
  cognito_client_id  = var.manage_cognito ? module.cognito[0].app_client_id : var.cognito_client_id
}

module "amplify" {
  count  = var.manage_amplify ? 1 : 0
  source = "../../modules/amplify"

  project_name = var.project_name
  environment  = var.environment

  repository_url      = var.amplify_repository_url
  github_access_token = var.amplify_github_access_token
  branch_name         = var.amplify_branch_name
  custom_domain       = var.amplify_custom_domain

  environment_variables = {
    VITE_API_BASE_URL = coalesce(var.ui_api_base_url, module.api_service.alb_url)
  }
}

module "api_service" {
  source = "../../modules/api_service"

  project_name = var.project_name
  environment  = var.environment
  aws_region   = var.aws_region

  vpc_id             = module.vpc.vpc_id
  public_subnet_ids  = module.vpc.public_subnet_ids
  private_subnet_ids = module.vpc.private_subnet_ids

  api_image = "${module.api_ecr.repository_url}:${var.api_image_tag}"

  database_secret_arn        = module.rds.database_secret_arn
  database_security_group_id = module.rds.database_security_group_id

  container_port = var.api_container_port
  desired_count  = var.api_desired_count

  use_private_subnets = var.use_private_subnets_for_api

  cognito_issuer_uri = local.cognito_issuer_uri
  cognito_client_id  = local.cognito_client_id

  certificate_arn         = var.api_certificate_arn
  alb_deletion_protection = var.alb_deletion_protection
  log_retention_days      = var.api_log_retention_days

  documents_bucket_arn = module.archive_s3.documents_bucket_arn

  additional_environment_variables = var.additional_api_environment_variables

  additional_secrets = {
    OPENAI_API_KEY = "${module.openai_secret.openai_secret_arn}:apiKey::"
  }
}
