data "aws_caller_identity" "current" {}

# Cross-variable guards Terraform's per-variable validation blocks can't
# express on their own (a variable's validation can only see itself).
resource "terraform_data" "config_guard" {
  lifecycle {
    precondition {
      condition     = var.manage_cognito || (var.cognito_issuer_uri != null && var.cognito_client_id != null && var.cognito_user_pool_id != null)
      error_message = "manage_cognito is false, so cognito_issuer_uri, cognito_client_id, and cognito_user_pool_id must all be set to an existing user pool's values."
    }

    precondition {
      condition     = var.manage_cognito || !var.manage_amplify || var.cognito_hosted_ui_domain != null
      error_message = "manage_cognito is false and manage_amplify is true, so cognito_hosted_ui_domain must be set to the existing user pool's Hosted UI domain (https://<domain>.auth.<region>.amazoncognito.com) so the UI build can configure Amplify's OAuth login."
    }

    precondition {
      condition = !var.manage_amplify || (
        (var.amplify_repository_url != null && var.amplify_github_access_token != null) ||
        (var.amplify_repository_url == null && var.amplify_github_access_token == null)
      )
      error_message = "manage_amplify is true: either set both amplify_repository_url and amplify_github_access_token (legacy PAT-based connection), or leave both unset and connect the repository manually via the AWS Console after the first apply (recommended - see README, avoids storing a GitHub token in Terraform state)."
    }

    precondition {
      condition     = !var.manage_amplify || var.amplify_custom_domain != null || (var.ui_redirect_url != null && var.ui_logout_url != null)
      error_message = "manage_amplify is true and amplify_custom_domain is not set, so Amplify's own URL is only known after the first apply (it's a randomly-assigned *.amplifyapp.com domain) - set ui_redirect_url/ui_logout_url explicitly (see README) or set amplify_custom_domain to a domain you control."
    }

    precondition {
      condition     = !var.use_private_subnets_for_api || var.enable_nat_gateway
      error_message = "use_private_subnets_for_api requires enable_nat_gateway = true, or the API will have no route to the public internet (needed for the live OpenAI provider)."
    }

    precondition {
      condition     = var.environment != "prod" || var.api_certificate_arn != null
      error_message = "api_certificate_arn is required in production - HTTP-only is not permitted for prod. Provide an ACM certificate ARN covering api_domain_name."
    }

    precondition {
      condition     = var.api_certificate_arn == null || var.api_domain_name != null
      error_message = "api_certificate_arn is set but api_domain_name is not - the ACM certificate must cover a real domain name, since the ALB's own DNS name is never a valid ACM certificate subject."
    }

    precondition {
      condition     = var.api_domain_name == null || var.api_route53_zone_id != null
      error_message = "api_domain_name is set but api_route53_zone_id is not - provide the Route53 hosted zone ID that api_domain_name belongs to, so Terraform can create the DNS record pointing it at the ALB."
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

  multi_az           = var.database_multi_az
  apply_immediately  = var.database_apply_immediately
  maintenance_window = var.database_maintenance_window
  backup_window      = var.database_backup_window
}

module "archive_s3" {
  source = "../../modules/s3"

  project_name   = var.project_name
  environment    = var.environment
  aws_account_id = data.aws_caller_identity.current.account_id
}

module "openai_secret" {
  count  = var.enable_openai_secret ? 1 : 0
  source = "../../modules/secrets"

  project_name = var.project_name
  environment  = var.environment

  recovery_window_in_days = var.openai_secret_recovery_window_days
}

# Oracle (KCOEUS) credentials for the Award Attachment loader
# (etl/load_award_attachments.py --ecs). Mirrors module.openai_secret's
# pattern exactly: Terraform creates only the empty secret container -
# no aws_secretsmanager_secret_version here, so the actual
# username/password/dsn value is never in Terraform state or source
# code, and must be populated out-of-band by an authorized operator
# (see docs/AWARD_ATTACHMENT_ECS_EXECUTION.md for the exact, safe
# `aws secretsmanager put-secret-value` command). Because Terraform
# never touches the version, re-applying never drifts or overwrites the
# value an operator sets by hand. Not wrapped in module.secrets to avoid
# reusing that module's OpenAI-specific output names
# (openai_secret_arn/openai_secret_name) for an unrelated secret.
resource "aws_secretsmanager_secret" "oracle" {
  name                    = "${var.project_name}/${var.environment}/oracle"
  description             = "Oracle (KCOEUS) credentials for the Award Attachment loader. Value is set out-of-band; see docs/AWARD_ATTACHMENT_ECS_EXECUTION.md."
  recovery_window_in_days = var.oracle_secret_recovery_window_days

  tags = {
    Name = "${var.project_name}-${var.environment}-oracle-secret"
  }
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

  documents_bucket_arn  = module.archive_s3.documents_bucket_arn
  documents_bucket_name = module.archive_s3.documents_bucket_name

  database_secret_arn        = module.rds.database_secret_arn
  database_security_group_id = module.rds.database_security_group_id

  oracle_secret_arn = aws_secretsmanager_secret.oracle.arn

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

  advanced_security_mode = var.cognito_advanced_security_mode
  deletion_protection    = var.cognito_deletion_protection
  mfa_configuration      = var.cognito_mfa_configuration
}

locals {
  # When manage_cognito is true, use the pool this configuration just
  # created; otherwise fall back to the existing pool's IDs supplied as
  # variables.
  cognito_issuer_uri       = var.manage_cognito ? module.cognito[0].issuer_uri : var.cognito_issuer_uri
  cognito_client_id        = var.manage_cognito ? module.cognito[0].app_client_id : var.cognito_client_id
  cognito_user_pool_id     = var.manage_cognito ? module.cognito[0].user_pool_id : var.cognito_user_pool_id
  cognito_hosted_ui_domain = var.manage_cognito ? module.cognito[0].hosted_ui_domain : var.cognito_hosted_ui_domain

  # Amplify.configure()'s OAuth `domain` field (and the manual Hosted UI
  # logout URL the UI builds) both expect a bare hostname, not a URL -
  # strip the https:// scheme carried by the value above.
  cognito_hosted_ui_domain_hostname = (
    local.cognito_hosted_ui_domain == null
    ? null
    : replace(local.cognito_hosted_ui_domain, "https://", "")
  )

  # Amplify's own default *.amplifyapp.com domain is only known after the
  # app is created (Terraform can't reference a resource's own computed
  # output as one of its own inputs), so it can only be auto-derived here
  # when a predictable custom_domain is configured. Otherwise the operator
  # must supply ui_redirect_url/ui_logout_url explicitly - see the
  # precondition above and the README.
  ui_redirect_url = coalesce(
    var.ui_redirect_url,
    var.amplify_custom_domain != null ? "https://${var.amplify_custom_domain}/" : null
  )
  ui_logout_url = coalesce(
    var.ui_logout_url,
    var.amplify_custom_domain != null ? "https://${var.amplify_custom_domain}/" : null
  )

  api_url = var.api_domain_name != null ? "https://${var.api_domain_name}" : module.api_service.alb_url
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
    VITE_API_BASE_URL         = coalesce(var.ui_api_base_url, local.api_url)
    VITE_AWS_REGION           = var.aws_region
    VITE_COGNITO_USER_POOL_ID = local.cognito_user_pool_id
    VITE_COGNITO_CLIENT_ID    = local.cognito_client_id
    VITE_COGNITO_DOMAIN       = local.cognito_hosted_ui_domain_hostname
    VITE_COGNITO_REDIRECT_URL = local.ui_redirect_url
    VITE_COGNITO_LOGOUT_URL   = local.ui_logout_url
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

  additional_secrets = var.enable_openai_secret ? {
    OPENAI_API_KEY = "${module.openai_secret[0].openai_secret_arn}:apiKey::"
  } : {}
}

resource "aws_route53_record" "api" {
  count = var.api_domain_name != null ? 1 : 0

  zone_id = var.api_route53_zone_id
  name    = var.api_domain_name
  type    = "A"

  alias {
    name                   = module.api_service.alb_dns_name
    zone_id                = module.api_service.alb_zone_id
    evaluate_target_health = true
  }
}
