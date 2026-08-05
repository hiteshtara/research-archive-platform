#
# Account safety
#

variable "expected_account_id" {
  description = "The 12-digit AWS account ID this environment must deploy into. terraform plan/apply refuses to proceed if the active credentials resolve to a different account. Run 'aws sts get-caller-identity' to confirm which account your credentials point to before running Terraform."
  type        = string

  validation {
    condition     = can(regex("^[0-9]{12}$", var.expected_account_id))
    error_message = "expected_account_id must be a 12-digit AWS account ID."
  }
}

#
# Core
#

variable "aws_region" {
  description = "AWS region used for this environment."
  type        = string
  default     = "us-east-1"
}

variable "project_name" {
  description = "Project name used to construct AWS resource names (e.g. \"research-archive-platform\"). Keep short and stable - changing it renames every resource."
  type        = string
  default     = "research-archive-platform"
}

variable "environment" {
  description = "Environment name. Used in resource names and to select environment-appropriate defaults elsewhere."
  type        = string

  validation {
    condition     = contains(["dev", "test", "prod"], var.environment)
    error_message = "environment must be one of: dev, test, prod."
  }
}

#
# Networking
#

variable "vpc_cidr" {
  description = "CIDR block for this environment's VPC."
  type        = string
}

variable "public_subnet_cidrs" {
  description = "CIDRs for public subnets (one per availability zone). Hosts the ALB and, unless use_private_subnets_for_api is true, the API's ECS tasks."
  type        = list(string)
}

variable "private_subnet_cidrs" {
  description = "CIDRs for private subnets (one per availability zone). Hosts RDS, and the ETL loader task."
  type        = list(string)
}

variable "availability_zones" {
  description = "Availability zones used by the subnets (must match the length and order of the subnet CIDR lists)."
  type        = list(string)
}

variable "enable_nat_gateway" {
  description = "Create a NAT Gateway so private-subnet workloads have general internet egress. Only needed if you also set use_private_subnets_for_api = true, since the ETL loader does not require internet access beyond the AWS services already reachable via VPC endpoints."
  type        = bool
  default     = false
}

variable "use_private_subnets_for_api" {
  description = "Run the API's ECS tasks in private subnets (no public IP) behind the ALB, instead of directly in public subnets with a public IP. Requires enable_nat_gateway = true, since the API calls the OpenAI API over the public internet when the live provider is enabled. Defaults to false to match the current, working topology; set true for a more defense-in-depth production posture."
  type        = bool
  default     = false
}

variable "database_allowed_cidrs" {
  description = "CIDR blocks allowed to connect to PostgreSQL directly (in addition to the API/loader security groups, which are always allowed). Leave empty unless you have a specific need (e.g. a bastion host or an office IP range) - prefer the SSM port-forwarding tunnel documented in the README instead."
  type        = list(string)
  default     = []
}

#
# Database
#

variable "database_name" {
  description = "Initial PostgreSQL database name."
  type        = string
  default     = "research_archive"
}

variable "database_master_username" {
  description = "PostgreSQL master username."
  type        = string
  default     = "archive_admin"
}

variable "database_instance_class" {
  description = "RDS instance class (e.g. db.t4g.micro for dev, db.r6g.large or larger for production)."
  type        = string
  default     = "db.r6g.large"
}

variable "database_allocated_storage" {
  description = "Initial allocated storage in GB."
  type        = number
  default     = 20
}

variable "database_max_allocated_storage" {
  description = "Maximum autoscaled storage in GB."
  type        = number
  default     = 100
}

variable "database_backup_retention_days" {
  description = "Automated RDS backup retention, in days. Use a longer window (e.g. 30) in production."
  type        = number
  default     = 30
}

variable "database_deletion_protection" {
  description = "Protect the RDS instance from accidental deletion. Should be true in test/prod."
  type        = bool
  default     = true
}

variable "database_skip_final_snapshot" {
  description = "Skip the final RDS snapshot on destroy. Should be false in test/prod so a snapshot is always taken before teardown."
  type        = bool
  default     = false
}

variable "database_multi_az" {
  description = "Run RDS with a synchronously-replicated standby in a second AZ for automatic failover. Should be true in production."
  type        = bool
  default     = true
}

variable "database_apply_immediately" {
  description = "Apply RDS modifications immediately instead of waiting for the next maintenance window. Should be false in production so disruptive changes land at a predictable, low-traffic time."
  type        = bool
  default     = false
}

variable "database_maintenance_window" {
  description = "Preferred weekly RDS maintenance window, e.g. \"sun:06:00-sun:07:00\" (UTC). Leave null to let AWS assign one."
  type        = string
  default     = "sun:06:00-sun:07:00"
}

variable "database_backup_window" {
  description = "Preferred daily RDS automated-backup window, e.g. \"05:00-05:30\" (UTC). Must not overlap database_maintenance_window. Leave null to let AWS assign one."
  type        = string
  default     = "05:00-05:30"
}

#
# Container images
#

variable "api_image_tag" {
  description = "Tag of the API container image to deploy (e.g. a git SHA or release tag). No default in production - you must pin an explicit tag; \"latest\" is a mutable tag and gives no guarantee of what is actually running."
  type        = string

  validation {
    condition     = var.api_image_tag != "latest"
    error_message = "api_image_tag must not be \"latest\" in production. Pin an explicit tag (git SHA or release version)."
  }
}

variable "loader_image_tag" {
  description = "Tag of the ETL loader container image to deploy. No default in production - pin an explicit tag."
  type        = string

  validation {
    condition     = var.loader_image_tag != "latest"
    error_message = "loader_image_tag must not be \"latest\" in production. Pin an explicit tag (git SHA or release version)."
  }
}

variable "ecr_image_tag_mutability" {
  description = "MUTABLE or IMMUTABLE for both ECR repositories. Use IMMUTABLE in production so a tag can never silently be repointed at a different image after push."
  type        = string
  default     = "IMMUTABLE"

  validation {
    condition     = contains(["MUTABLE", "IMMUTABLE"], var.ecr_image_tag_mutability)
    error_message = "ecr_image_tag_mutability must be MUTABLE or IMMUTABLE."
  }
}

variable "ecr_force_delete" {
  description = "Allow ECR repositories to be destroyed even if they still contain images. Recommended false in production."
  type        = bool
  default     = false
}

#
# API service
#

variable "api_container_port" {
  description = "Port the Spring Boot API container listens on."
  type        = number
  default     = 8080
}

variable "api_desired_count" {
  description = "Number of API tasks to run."
  type        = number
  default     = 1
}

variable "api_log_retention_days" {
  description = "CloudWatch Logs retention for the API, in days."
  type        = number
  default     = 14
}

variable "loader_log_retention_days" {
  description = "CloudWatch Logs retention for the ETL loader, in days."
  type        = number
  default     = 14
}

variable "alb_deletion_protection" {
  description = "Protect the API load balancer from accidental deletion. Should be true in test/prod."
  type        = bool
  default     = true
}

#
# Domain / TLS
#

variable "api_domain_name" {
  description = "Custom domain name for the API (e.g. api.archive.bu.edu). Optional - leave null to use the ALB's default DNS name."
  type        = string
  default     = null
}

variable "api_certificate_arn" {
  description = "ACM certificate ARN for the API load balancer's HTTPS listener, covering api_domain_name (the ALB's default DNS name is not valid for ACM, so this is only usable with a custom domain). Required in production - terraform plan/apply refuses to proceed with HTTP-only in prod (see the config_guard precondition in main.tf). Before applying, confirm the certificate actually covers api_domain_name: `aws acm describe-certificate --certificate-arn <arn> --query 'Certificate.SubjectAlternativeNames'` (Terraform cannot verify SAN coverage from an ARN alone - there is no AWS provider data source for it)."
  type        = string
  default     = null
}

variable "api_route53_zone_id" {
  description = "Route53 hosted zone ID that api_domain_name belongs to. Required when api_domain_name is set (which is effectively always, in production - see the config_guard precondition) - Terraform creates an ALIAS record for api_domain_name in this zone pointing at the ALB. This zone must already exist (e.g. bu.edu or a delegated subdomain); Terraform does not create hosted zones for a domain it doesn't own."
  type        = string
  default     = null
}

#
# Cognito
#
# This configuration does not create a Cognito User Pool unless
# manage_cognito is true. When false (the default, matching the existing
# dev environment, which already has a manually-created pool), you must
# supply cognito_issuer_uri/cognito_client_id yourselves.

variable "manage_cognito" {
  description = "Create a Cognito User Pool and app client via Terraform. Set false to bring your own existing pool via cognito_issuer_uri/cognito_client_id instead."
  type        = bool
  default     = false
}

variable "cognito_issuer_uri" {
  description = "OIDC issuer URI for an existing Cognito User Pool. Required when manage_cognito = false; ignored when true (the created pool's issuer URI is used instead)."
  type        = string
  default     = null
}

variable "cognito_client_id" {
  description = "App client ID for an existing Cognito User Pool. Required when manage_cognito = false; ignored when true."
  type        = string
  default     = null
}

variable "cognito_user_pool_id" {
  description = "User Pool ID for an existing Cognito User Pool (e.g. us-east-1_abc123def). Required when manage_cognito = false; ignored when true. Needed separately from cognito_issuer_uri because the UI's Amplify.configure() call needs the bare pool ID, not the issuer URL."
  type        = string
  default     = null
}

variable "cognito_hosted_ui_domain" {
  description = "Full Hosted UI domain URL for an existing Cognito User Pool, e.g. https://my-domain.auth.us-east-1.amazoncognito.com. Required when manage_cognito = false and manage_amplify = true; ignored otherwise."
  type        = string
  default     = null
}

variable "cognito_callback_urls" {
  description = "Allowed OAuth callback URLs for the Cognito app client. Only used when manage_cognito = true."
  type        = list(string)
  default     = []
}

variable "cognito_logout_urls" {
  description = "Allowed OAuth logout URLs for the Cognito app client. Only used when manage_cognito = true."
  type        = list(string)
  default     = []
}

variable "cognito_hosted_ui_domain_prefix" {
  description = "Domain prefix for the Cognito Hosted UI, if wanted. Only used when manage_cognito = true."
  type        = string
  default     = null
}

variable "cognito_advanced_security_mode" {
  description = "Cognito advanced (threat-protection) security mode: OFF, AUDIT, or ENFORCED. Only used when manage_cognito = true."
  type        = string
  default     = "ENFORCED"

  validation {
    condition     = contains(["OFF", "AUDIT", "ENFORCED"], var.cognito_advanced_security_mode)
    error_message = "cognito_advanced_security_mode must be one of: OFF, AUDIT, ENFORCED."
  }
}

variable "cognito_deletion_protection" {
  description = "Protect the Cognito User Pool from accidental deletion via the Cognito API/console. Should be ACTIVE in production. Only used when manage_cognito = true."
  type        = string
  default     = "ACTIVE"

  validation {
    condition     = contains(["ACTIVE", "INACTIVE"], var.cognito_deletion_protection)
    error_message = "cognito_deletion_protection must be one of: ACTIVE, INACTIVE."
  }
}

variable "cognito_mfa_configuration" {
  description = "Require multi-factor authentication: OFF, ON, or OPTIONAL (TOTP/software-token only). Only used when manage_cognito = true."
  type        = string
  default     = "OPTIONAL"

  validation {
    condition     = contains(["OFF", "ON", "OPTIONAL"], var.cognito_mfa_configuration)
    error_message = "cognito_mfa_configuration must be one of: OFF, ON, OPTIONAL."
  }
}

#
# Amplify / UI hosting
#
# As with Cognito, this does not create anything unless manage_amplify is
# true. GitHub tokens must never be committed - set via
# TF_VAR_amplify_github_access_token or an equivalent secret-injection
# mechanism, never in a tfvars file.

variable "manage_amplify" {
  description = "Create an Amplify app for the UI via Terraform. Set false if the UI is hosted some other way (e.g. an existing, manually-configured Amplify app)."
  type        = bool
  default     = false
}

variable "amplify_repository_url" {
  description = "HTTPS URL of the GitHub repository Amplify builds from. Only used when manage_amplify = true. Recommended: leave unset (with amplify_github_access_token also unset) - Terraform creates the app with no repository connected, then connect it manually via the AWS Console's GitHub App-based flow after the first apply (see terraform/README.md's 'Amplify repository connection' section). Only set both this and the token together for the legacy PAT-based flow."
  type        = string
  default     = null
}

variable "amplify_github_access_token" {
  description = "GitHub personal access token (repo scope) for Amplify's repository connection, for the legacy PAT-based flow only - see amplify_repository_url. Only used when manage_amplify = true and amplify_repository_url is set. Never set this in a tfvars file - pass it via TF_VAR_amplify_github_access_token or a secret manager."
  type        = string
  default     = null
  sensitive   = true
}

variable "amplify_branch_name" {
  description = "Git branch Amplify builds and deploys. Only used when manage_amplify = true."
  type        = string
  default     = "main"
}

variable "amplify_custom_domain" {
  description = "Custom domain to attach to the Amplify app (e.g. archive.bu.edu). Only used when manage_amplify = true; leave null to use the default amplifyapp.com domain."
  type        = string
  default     = null
}

variable "ui_api_base_url" {
  description = "Value of the UI's VITE_API_BASE_URL build-time environment variable. Defaults to the API's own computed URL if left null; override only if the UI should point somewhere else (e.g. a shared API across environments)."
  type        = string
  default     = null
}

variable "ui_redirect_url" {
  description = "The UI's own URL, used as Cognito's OAuth sign-in redirect target (VITE_COGNITO_REDIRECT_URL) - must also be included in cognito_callback_urls. Defaults to https://<amplify_custom_domain>/ when that's set; otherwise required, since Amplify's own *.amplifyapp.com domain is only known after the first apply (a resource can't reference its own computed output as one of its own inputs)."
  type        = string
  default     = null
}

variable "ui_logout_url" {
  description = "The UI's own URL, used as Cognito's OAuth sign-out redirect target (VITE_COGNITO_LOGOUT_URL) - must also be included in cognito_logout_urls. Same default/requirement rules as ui_redirect_url."
  type        = string
  default     = null
}

#
# AI feature (optional)
#
# The OpenAI integration is off by default end-to-end: no secret is
# created, no OPENAI_API_KEY is injected into the API task, and the
# execution role is granted no access to it. Set enable_openai_secret =
# true (and the matching additional_api_environment_variables APP_AI_*
# flags below) only once you intend to actually use the live provider.

variable "enable_openai_secret" {
  description = "Create the OpenAI Secrets Manager secret and inject OPENAI_API_KEY into the API task. Leave false (the default) to deploy without ever requiring an OpenAI secret value - the API falls back to the deterministic stub AI provider."
  type        = bool
  default     = false
}

variable "openai_secret_recovery_window_days" {
  description = "Secrets Manager recovery window for the OpenAI API key secret. Use 0 only in non-production environments."
  type        = number
  default     = 7
}

variable "additional_api_environment_variables" {
  description = "Extra plaintext environment variables for the API container, e.g. { APP_AI_ENABLED = \"true\", APP_AI_PROVIDER = \"openai\", APP_AI_OPENAI_ENABLED = \"true\" }. See docs/runbooks/ecs-ai-deployment.md for the full set of APP_AI_* flags."
  type        = map(string)
  default     = {}
}
