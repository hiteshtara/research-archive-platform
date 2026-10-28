data "aws_caller_identity" "current" {}

#
# API image rollback guard - see var.api_image_rollback_guard.
#
# ops/deploy-api.sh builds and deploys API images directly to ECR/ECS, so
# var.api_image_tag in terraform.tfvars drifts behind reality. On 2026-10-22
# the pin was 209 commits behind the live image, and applying an unrelated CORS
# change would have reverted the API to the 2026-08-10 build - including
# 846a840 "fix(api): make the application start again".
#
# These data sources read whatever is registered on the newest ACTIVE revision
# of the task family. The precondition in terraform_data.config_guard then
# fails the PLAN - not the apply - if the pin disagrees.
#
data "aws_ecs_task_definition" "api_live" {
  count = var.api_image_rollback_guard ? 1 : 0

  task_definition = "${var.project_name}-${var.environment}-api"
}

data "aws_ecs_container_definition" "api_live" {
  count = var.api_image_rollback_guard ? 1 : 0

  task_definition = data.aws_ecs_task_definition.api_live[0].id
  container_name  = "api"
}

locals {
  # Single source of truth for the API image this configuration intends to
  # run, used both by module.api_service and by the rollback guard below.
  api_image_desired = "${module.api_ecr.repository_url}:${var.api_image_tag}"
}

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

    # A precondition requiring amplify_github_access_token whenever
    # amplify_repository_url is set once lived here, guarding against a
    # half-configured initial attach. Removed once the repository was
    # actually attached (see AWARD_IMPLEMENTATION_ROADMAP.md's
    # "Fourteenth same-day follow-up") - access_token/oauth_token are
    # in modules/amplify's own ignore_changes (AWS never returns them,
    # so Terraform never manages them on an ongoing basis regardless),
    # meaning this precondition had no further purpose after the first
    # successful attach and only added friction to every later plan for
    # anything else in this file (e.g. Cognito callback URL changes).
    # If repository_url is ever changed to a *different* repo, attach
    # it the same way documented in modules/amplify/variables.tf's
    # github_access_token doc - a one-off `aws amplify update-app
    # --access-token ...` CLI call, not a stored Terraform variable.

    precondition {
      condition     = !var.manage_amplify || var.amplify_custom_domain != null || (var.ui_redirect_url != null && var.ui_logout_url != null)
      error_message = "manage_amplify is true and amplify_custom_domain is not set, so Amplify's own URL is only known after the first apply (it's a randomly-assigned *.amplifyapp.com domain) - set ui_redirect_url/ui_logout_url explicitly (see README) or set amplify_custom_domain to a domain you control."
    }

    # Without this, enabling the tunnel and forgetting db_tunnel_image
    # silently reverts to reading the live loader task definition - so an
    # unrelated ETL deploy replaces the tunnel's task definition again, and
    # the image is whatever the loader happens to run rather than the
    # protected, digest-pinned one. The fallback stays in the module for
    # compatibility; it just may not be reached by accident here.
    precondition {
      condition     = !var.manage_db_tunnel_access || var.db_tunnel_image != null
      error_message = "manage_db_tunnel_access is true, so db_tunnel_image must be set to a digest-pinned image (…@sha256:<64 hex>) that also carries a protected-* ECR tag. Leaving it null makes the tunnel follow the live loader image, which couples it to ETL deploys and risks the image being expired by the loader repository's lifecycle policy. See docs/runbooks/DB_TUNNEL_IMAGE_PINNING.md."
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

    precondition {
      condition     = var.amplify_custom_domain == null || var.ui_route53_zone_id != null
      error_message = "amplify_custom_domain is set but ui_route53_zone_id is not - provide the Route53 hosted zone ID that amplify_custom_domain belongs to, so Terraform can publish Amplify's certificate-validation record and the domain's traffic record. Note the parent zone kuali-archive.bu.edu is not in this account; use the delegated child zone."
    }

    precondition {
      condition = (
        !var.enable_oracle_peering ||
        (
          var.oracle_vpc_id != null &&
          length(var.oracle_subnet_cidrs) > 0 &&
          length(var.oracle_route_table_ids) > 0 &&
          var.oracle_security_group_id != null
        )
      )
      error_message = "enable_oracle_peering is true, so oracle_vpc_id, oracle_subnet_cidrs, oracle_route_table_ids, and oracle_security_group_id must all be set - see docs/ORACLE_STAGING_CONNECTIVITY.md."
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

  enable_oracle_peering  = var.enable_oracle_peering
  oracle_vpc_id          = var.oracle_vpc_id
  oracle_subnet_cidrs    = var.oracle_subnet_cidrs
  oracle_route_table_ids = var.oracle_route_table_ids
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

  oracle_secret_arn        = aws_secretsmanager_secret.oracle.arn
  oracle_security_group_id = var.oracle_security_group_id

  log_retention_days = var.loader_log_retention_days
}

# Detached, unattended nightly Subaward business-data sync - see
# docs/runbooks/SUBAWARD_NIGHTLY_SYNC.md. Runs the existing loader image
# as a one-off ECS Fargate RunTask (never a persistent service), on the
# same cluster/subnets/security group as every other one-off loader
# invocation - creates no EC2, NAT gateway, VPC endpoint, or public
# database access.
module "subaward_sync_schedule" {
  source = "../../modules/subaward_sync_schedule"

  project_name = var.project_name
  environment  = var.environment
  aws_region   = var.aws_region
  account_id   = data.aws_caller_identity.current.account_id

  ecs_cluster_arn        = module.loader_ecs.cluster_arn
  task_definition_family = module.loader_ecs.task_definition_family
  execution_role_arn     = module.loader_ecs.execution_role_arn
  task_role_arn          = module.loader_ecs.loader_task_role_arn
  private_subnet_ids     = module.vpc.private_subnet_ids
  security_group_id      = module.loader_ecs.loader_security_group_id
  log_group_name         = "/ecs/${var.project_name}-${var.environment}-loader"

  # Explicitly pinned, reviewed revision - NOT family-latest.
  # Rev 237 = image 20260817T011034Z-4a82821, built from committed commit
  # 4a82821 (present on bu/main and origin/main), which contains
  # --sync-all and the Subaward Stage-2 duplicate-batch fix, and whose
  # image is still present in ECR.
  # Deliberately NOT rev 245 (evidence-idx-20260817T144632Z): that image
  # was built from a dirty working tree including untracked CARBX files
  # and is not reproducible from any commit.
  loader_task_definition_revision = 237

  # Must resolve to the same manifest as the pinned revision's image.
  # Protected by modules/ecr's priority-1 lifecycle rule.
  protected_image_tag = "protected-20260817T011034Z-4a82821"
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

  advanced_security_mode    = var.cognito_advanced_security_mode
  deletion_protection       = var.cognito_deletion_protection
  mfa_configuration         = var.cognito_mfa_configuration
  allow_admin_password_auth = var.cognito_allow_admin_password_auth

  attachment_viewer_usernames = var.cognito_attachment_viewer_usernames

  shibboleth_idp_metadata_url        = var.shibboleth_idp_metadata_url
  shibboleth_require_signed_requests = var.shibboleth_require_signed_requests
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

  # Unlike ui_redirect_url/ui_logout_url above, this has no circular
  # dependency problem: module.amplify's own inputs never depend on the
  # API's CORS configuration, only the reverse, so Terraform can create
  # the Amplify app first and use its real domain here within the same
  # apply.
  #
  # Deliberately built from module.amplify[0].default_domain (an
  # app-level attribute) and the plain var.amplify_branch_name, NOT
  # from module.amplify[0].branch_url - branch_url is derived from
  # aws_amplify_branch.main's own branch_name attribute, which makes
  # anything consuming it transitively depend on that branch *resource*
  # existing. That dependency is real, not cosmetic: destroying/
  # recreating the branch (e.g. to convert it from a manually-deployed
  # branch to a Git-backed one) previously cascaded into a plan to also
  # destroy and recreate the API's ECS task definition/service, found
  # via `terraform plan -destroy -target=...aws_amplify_branch.main`
  # before this fix - see docs/architecture/AWARD_IMPLEMENTATION_ROADMAP.md's
  # "Fourteenth same-day follow-up".
  # The *.amplifyapp.com origin is deliberately KEPT alongside the custom
  # domain, not replaced: it stays operational as a fallback, and the API must
  # accept browser requests from whichever origin actually served the UI.
  # Without the custom domain here, the UI at https://nonprod.kuali-archive.bu.edu
  # loads but every API call fails CORS - the page renders and then errors.
  cors_allowed_origins = compact(concat(
    ["http://localhost:5173"],
    var.manage_amplify
    ? ["https://${var.amplify_branch_name}.${module.amplify[0].default_domain}"]
    : [],
    var.amplify_custom_domain != null ? ["https://${var.amplify_custom_domain}"] : []
  ))
}

locals {
  # Single source of truth for the UI-side Explorer flag, so it can be
  # asserted against the API-side APP_EXPLORER_ENABLED below rather than
  # drifting silently the way it did in the 2026-08-10 investigation
  # (VITE_EXPLORER_ENABLED=true + APP_EXPLORER_ENABLED unset -> the nav/
  # route rendered but every request 404'd, masked as a 403 until the
  # /error permitAll fix). Never set in test/prod.
  explorer_enabled_ui = true
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

  # VITE_COGNITO_IDENTITY_PROVIDER and VITE_CANONICAL_ORIGIN are each merged
  # in only when their variable is set, so this module proposes NO change to
  # the Amplify app while either is unconfigured. Emitting a key with an empty
  # value would instead show up as an environment-variable change and trigger
  # a rebuild for no behavioural gain.
  environment_variables = merge({
    VITE_API_BASE_URL         = coalesce(var.ui_api_base_url, local.api_url)
    VITE_AWS_REGION           = var.aws_region
    VITE_COGNITO_USER_POOL_ID = local.cognito_user_pool_id
    VITE_COGNITO_CLIENT_ID    = local.cognito_client_id
    VITE_COGNITO_DOMAIN       = local.cognito_hosted_ui_domain_hostname
    VITE_COGNITO_REDIRECT_URL = local.ui_redirect_url
    VITE_COGNITO_LOGOUT_URL   = local.ui_logout_url
    # Approved origins the UI may redirect back to after login/logout, so one
    # build served at several approved addresses returns the user to whichever
    # address they started at (ui/src/features/auth/authRedirects.mjs).
    #
    # Derived from cognito_callback_urls rather than restated, so the browser's
    # allow-list cannot drift from the Cognito app client's own callback list -
    # anything Cognito would reject with redirect_mismatch is absent here too.
    # An origin not on this list is ignored and VITE_COGNITO_REDIRECT_URL is
    # used instead, so this never widens what Cognito already permits.
    VITE_COGNITO_ALLOWED_ORIGINS = join(",", var.cognito_callback_urls)
    # Archive Explorer (Phase 2, /explorer) - dev-only developer tool,
    # hidden entirely (nav item + route) unless this matches the API's
    # own APP_EXPLORER_ENABLED. See docs/ARCHIVE_EXPLORER.md. Never set
    # in test/prod.
    VITE_EXPLORER_ENABLED = local.explorer_enabled_ui ? "true" : "false"
    },
    var.ui_identity_provider == null ? {} : {
      VITE_COGNITO_IDENTITY_PROVIDER = var.ui_identity_provider
    },
    var.ui_canonical_origin == null ? {} : {
      VITE_CANONICAL_ORIGIN = var.ui_canonical_origin
    }
  )
}

#
# API HTTPS certificate - provisioned here (DNS validation against
# api_route53_zone_id, fully automatable with no manual step since we
# own that zone in this same account) unless an existing certificate
# ARN is supplied via api_certificate_arn instead. Fixes the browser's
# mixed-content block (HTTPS Amplify UI calling an HTTP-only ALB) - see
# docs/architecture/AWARD_IMPLEMENTATION_ROADMAP.md's "Fifteenth
# same-day follow-up".
#
resource "aws_acm_certificate" "api" {
  count = (var.api_domain_name != null && var.api_certificate_arn == null) ? 1 : 0

  domain_name       = var.api_domain_name
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_route53_record" "api_certificate_validation" {
  for_each = (var.api_domain_name != null && var.api_certificate_arn == null) ? {
    for dvo in aws_acm_certificate.api[0].domain_validation_options : dvo.domain_name => {
      name   = dvo.resource_record_name
      record = dvo.resource_record_value
      type   = dvo.resource_record_type
    }
  } : {}

  zone_id = var.api_route53_zone_id
  name    = each.value.name
  type    = each.value.type
  records = [each.value.record]
  ttl     = 60

  allow_overwrite = true
}

resource "aws_acm_certificate_validation" "api" {
  count = (var.api_domain_name != null && var.api_certificate_arn == null) ? 1 : 0

  certificate_arn = aws_acm_certificate.api[0].arn
  validation_record_fqdns = [
    for record in aws_route53_record.api_certificate_validation : record.fqdn
  ]
}

locals {
  api_certificate_arn_effective = (
    var.api_certificate_arn != null
    ? var.api_certificate_arn
    : (var.api_domain_name != null ? aws_acm_certificate_validation.api[0].certificate_arn : null)
  )
}

module "api_service" {
  source = "../../modules/api_service"

  project_name = var.project_name
  environment  = var.environment
  aws_region   = var.aws_region

  vpc_id             = module.vpc.vpc_id
  public_subnet_ids  = module.vpc.public_subnet_ids
  private_subnet_ids = module.vpc.private_subnet_ids

  api_image = local.api_image_desired

  # Rollback guard input - see modules/api_service's expected_live_image and
  # var.api_image_rollback_guard.
  expected_live_image = var.api_image_rollback_guard ? try(data.aws_ecs_container_definition.api_live[0].image, null) : null

  database_secret_arn        = module.rds.database_secret_arn
  database_security_group_id = module.rds.database_security_group_id

  container_port = var.api_container_port
  desired_count  = var.api_desired_count

  use_private_subnets = var.use_private_subnets_for_api

  cognito_issuer_uri = local.cognito_issuer_uri
  cognito_client_id  = local.cognito_client_id

  certificate_arn         = local.api_certificate_arn_effective
  alb_deletion_protection = var.alb_deletion_protection
  log_retention_days      = var.api_log_retention_days

  documents_bucket_arn  = module.archive_s3.documents_bucket_arn
  documents_bucket_name = module.archive_s3.documents_bucket_name

  additional_environment_variables = merge(
    var.additional_api_environment_variables,
    { APP_CORS_ALLOWED_ORIGINS = join(",", local.cors_allowed_origins) }
  )

  # container_secrets in modules/api_service is
  # merge(base_secrets, var.additional_secrets), and the later map wins -
  # so these five override the owner-credential defaults built from
  # var.database_secret_arn. The module also derives the execution role's
  # secretsmanager:GetSecretValue grant from the ARNs referenced here, so
  # no separate IAM change is needed.
  #
  # Nothing else about the task definition changes: same image, same
  # environment, same roles. The API reads POSTGRES_* and does not care
  # which database role it is.
  additional_secrets = merge(
    var.enable_openai_secret ? {
      OPENAI_API_KEY = "${try(module.openai_secret[0].openai_secret_arn, "")}:apiKey::"
    } : {},
    var.api_database_secret_arn == null ? {} : {
      POSTGRES_HOST     = "${var.api_database_secret_arn}:host::"
      POSTGRES_PORT     = "${var.api_database_secret_arn}:port::"
      POSTGRES_DB       = "${var.api_database_secret_arn}:dbname::"
      POSTGRES_USER     = "${var.api_database_secret_arn}:username::"
      POSTGRES_PASSWORD = "${var.api_database_secret_arn}:password::"
    },
  )
}

# Hard-fails plan/apply if the UI's Explorer flag is on while the API's
# isn't - the exact drift that caused the 2026-08-10 Proposal Explorer
# investigation. Zero-side-effect (terraform_data has no real resource
# behind it); only its precondition matters.
resource "terraform_data" "explorer_flags_match" {
  input = local.explorer_enabled_ui

  lifecycle {
    precondition {
      condition = (
        !local.explorer_enabled_ui
        || try(var.additional_api_environment_variables["APP_EXPLORER_ENABLED"], "false") == "true"
      )
      error_message = "local.explorer_enabled_ui (VITE_EXPLORER_ENABLED) is true but additional_api_environment_variables[\"APP_EXPLORER_ENABLED\"] is not \"true\" - the UI would show the Explorer nav/route while the API's ExplorerController never registers, so every request 404s. Set APP_EXPLORER_ENABLED = \"true\" in terraform.tfvars to match, or turn off explorer_enabled_ui."
    }
  }
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

#
# UI custom domain DNS - published into the delegated child zone
# nonprod.kuali-archive.bu.edu, which this account owns
# (Z02648057ZNNZSP3RPBA). The PARENT zone kuali-archive.bu.edu is in another
# AWS account and is deliberately untouched: it already delegates "nonprod" to
# us, and its apex is reserved for the eventual production application.
#
# Amplify returns both records as single space-separated
# "<name> <TYPE> <value>" strings, and ONLY after the domain association
# exists. That is why publishing is gated on ui_publish_dns_records rather than
# on the parsed values themselves: a count/for_each derived from an
# as-yet-uncreated resource's attribute is unknown at plan time, which makes
# "terraform plan" fail outright with "Invalid count argument". The gate is a
# plain bool, so both phases plan cleanly:
#
#   apply 1 - ui_publish_dns_records = false -> association created, records 0
#   read    - terraform output ui_custom_domain_dns_records_required
#   apply 2 - ui_publish_dns_records = true  -> the two records published
#
locals {
  ui_cert_record_parts = compact(split(
    " ",
    trimspace(coalesce(try(module.amplify[0].custom_domain_certificate_verification_record, ""), ""))
  ))

  ui_cert_validation = length(local.ui_cert_record_parts) >= 3 ? {
    name  = trimsuffix(local.ui_cert_record_parts[0], ".")
    type  = local.ui_cert_record_parts[1]
    value = trimsuffix(local.ui_cert_record_parts[2], ".")
  } : null

  # Target of the domain's own traffic record - an Amplify-fronted CloudFront
  # distribution.
  #
  # Amplify documents these as "<name> <TYPE> <value>", but for a record at the
  # ZONE APEX the name is empty, so the string arrives as
  # " CNAME d1mgq4btdvw5k9.cloudfront.net" - two fields once compacted, not
  # three. Take the value as the LAST field so both shapes parse. Found during
  # the 2026-10-22 rollout: an index-2 lookup behind a ">= 3" length filter
  # silently produced null for the apex record, which would have been applied
  # as an ALIAS with a null target.
  ui_traffic_targets = [
    for raw in try(module.amplify[0].custom_domain_sub_domain_records, []) :
    trimsuffix(one(slice(
      compact(split(" ", trimspace(raw))),
      length(compact(split(" ", trimspace(raw)))) - 1,
      length(compact(split(" ", trimspace(raw))))
    )), ".")
    if length(compact(split(" ", trimspace(raw)))) >= 2
  ]

  ui_traffic_target = length(local.ui_traffic_targets) > 0 ? local.ui_traffic_targets[0] : null
}

resource "aws_route53_record" "ui_certificate_validation" {
  for_each = var.ui_publish_dns_records ? { validation = local.ui_cert_validation } : {}

  zone_id = var.ui_route53_zone_id
  name    = each.value.name
  type    = each.value.type
  records = [each.value.value]
  ttl     = 60

  # Amplify re-issues this record on certificate renewal; allow_overwrite stops
  # a renewal from failing the apply on an already-present record.
  allow_overwrite = true
}

# amplify_custom_domain is the APEX of the zone it sits in
# (nonprod.kuali-archive.bu.edu), and Route53 forbids a CNAME at a zone apex.
# Amplify hands back a CNAME-shaped record, so it is published as an ALIAS A
# record to the same CloudFront target instead. Z2FDTNDATAQYW2 is AWS's fixed
# global hosted-zone ID for CloudFront - identical in every account and region,
# not a value derived from this stack.
resource "aws_route53_record" "ui" {
  count = var.ui_publish_dns_records ? 1 : 0

  # Fail loudly rather than publishing a record with a null target. Without
  # this, a parsing miss produces an ALIAS pointing nowhere.
  lifecycle {
    precondition {
      condition     = local.ui_traffic_target != null
      error_message = "Could not determine the Amplify traffic target for amplify_custom_domain. Check `terraform output ui_custom_domain_dns_records_required` - the domain association must exist and report its sub_domain dns_record before ui_publish_dns_records is set true."
    }
  }

  zone_id = var.ui_route53_zone_id
  name    = var.amplify_custom_domain
  type    = "A"

  alias {
    name                   = local.ui_traffic_target
    zone_id                = "Z2FDTNDATAQYW2"
    evaluate_target_health = false
  }
}

#
# CloudShell analyst read-only PostgreSQL access. Restored 2026-10-22 - these
# three resources and their variable were applied to AWS but their declaration
# only ever existed in the uncommitted working tree preserved at 27f4dd5, so
# main did not manage them and an untargeted plan offered to DESTROY them.
# See docs/operations/TERRAFORM_DRIFT_RECONCILIATION.md and
# docs/runbooks/CLOUDSHELL_ANALYSIS.md.
#

resource "aws_security_group" "cloudshell_analyst" {
  count = var.manage_cloudshell_analyst_access ? 1 : 0

  name        = "${var.project_name}-${var.environment}-cloudshell-analyst-sg"
  description = "CloudShell VPC environment ENI for read-only PostgreSQL analysis - select this security group when creating the VPC environment"
  vpc_id      = module.vpc.vpc_id

  # ingress is explicitly empty, so Terraform actively keeps this SG at
  # genuinely zero inbound (the paired RDS-side ingress rule lives on the
  # database security group, not here).
  ingress = []

  # egress is deliberately NOT declared inline, which is a correction to the
  # preserved 27f4dd5 version. That version set `egress = []` to force
  # Terraform to revoke the implicit AWS-default allow-all-outbound rule a new
  # security group receives. It achieved that, but it also conflicts with
  # aws_vpc_security_group_egress_rule.cloudshell_analyst_to_database below:
  # AWS reports that separately-managed rule back through this resource's
  # `egress` attribute, so `egress = []` makes every plan propose revoking the
  # one rule this SG exists to provide. Verified 2026-10-22 - a plan with
  # `egress = []` proposed removing the 5432-to-RDS rule, and applying it
  # would have broken the CloudShell path and then re-added it on the next
  # apply, forever.
  #
  # The implicit allow-all-outbound rule is already revoked on the live SG
  # (sg-002be83bf1cf249fa) by the original apply, so omitting `egress` here
  # changes nothing today and leaves the single 5432 rule as the only egress.
  # If this SG is ever destroyed and recreated, re-check that the default
  # allow-all-outbound rule is gone, because Terraform will no longer revoke
  # it automatically.

  tags = {
    Name = "${var.project_name}-${var.environment}-cloudshell-analyst-sg"
  }
}

resource "aws_vpc_security_group_egress_rule" "cloudshell_analyst_to_database" {
  count = var.manage_cloudshell_analyst_access ? 1 : 0

  security_group_id            = aws_security_group.cloudshell_analyst[0].id
  referenced_security_group_id = module.rds.database_security_group_id

  from_port   = 5432
  to_port     = 5432
  ip_protocol = "tcp"

  description = "PostgreSQL to dev RDS from a CloudShell VPC environment session"
}

resource "aws_vpc_security_group_ingress_rule" "database_from_cloudshell_analyst" {
  count = var.manage_cloudshell_analyst_access ? 1 : 0

  security_group_id            = module.rds.database_security_group_id
  referenced_security_group_id = aws_security_group.cloudshell_analyst[0].id

  from_port   = 5432
  to_port     = 5432
  ip_protocol = "tcp"

  description = "PostgreSQL access from a CloudShell VPC environment session (read-only analysis) - see docs/runbooks/CLOUDSHELL_ANALYSIS.md"
}

