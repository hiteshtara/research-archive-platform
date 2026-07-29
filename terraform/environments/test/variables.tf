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
  default     = "db.t4g.micro"
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
  default     = 7
}

variable "database_deletion_protection" {
  description = "Protect the RDS instance from accidental deletion. Should be true in test/prod."
  type        = bool
  default     = false
}

variable "database_skip_final_snapshot" {
  description = "Skip the final RDS snapshot on destroy. Should be false in test/prod so a snapshot is always taken before teardown."
  type        = bool
  default     = true
}

#
# Container images
#

variable "api_image_tag" {
  description = "Tag of the API container image to deploy (e.g. a git SHA or release tag). Avoid \"latest\" for anything beyond a quick dev bring-up - it is a mutable tag and gives no guarantee of what is actually running."
  type        = string
  default     = "latest"
}

variable "loader_image_tag" {
  description = "Tag of the ETL loader container image to deploy."
  type        = string
  default     = "latest"
}

variable "ecr_image_tag_mutability" {
  description = "MUTABLE or IMMUTABLE for both ECR repositories. Use IMMUTABLE in production so a tag can never silently be repointed at a different image after push."
  type        = string
  default     = "MUTABLE"

  validation {
    condition     = contains(["MUTABLE", "IMMUTABLE"], var.ecr_image_tag_mutability)
    error_message = "ecr_image_tag_mutability must be MUTABLE or IMMUTABLE."
  }
}

variable "ecr_force_delete" {
  description = "Allow ECR repositories to be destroyed even if they still contain images. Recommended false in production."
  type        = bool
  default     = true
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
  default     = false
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
  description = "ACM certificate ARN for the API load balancer's HTTPS listener, covering api_domain_name (or the ALB's default DNS name is not valid for ACM, so this is only usable with a custom domain). Leave null to serve HTTP only, which is only appropriate for initial bring-up before a certificate exists."
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
  description = "HTTPS URL of the GitHub repository Amplify builds from. Required when manage_amplify = true."
  type        = string
  default     = null
}

variable "amplify_github_access_token" {
  description = "GitHub personal access token (repo scope) for Amplify's repository connection. Required when manage_amplify = true. Never set this in a tfvars file - pass it via TF_VAR_amplify_github_access_token or a secret manager."
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

#
# AI feature (optional)
#

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
