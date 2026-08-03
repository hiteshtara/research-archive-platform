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

variable "vpc_id" {
  description = "VPC ID."
  type        = string
}

variable "public_subnet_ids" {
  description = "Public subnets used by the application load balancer."
  type        = list(string)
}

variable "private_subnet_ids" {
  description = "Private subnets used by ECS tasks."
  type        = list(string)
}

variable "api_image" {
  description = "Complete API container image reference."
  type        = string
}

variable "database_secret_arn" {
  description = "Secrets Manager ARN containing PostgreSQL connection details."
  type        = string
}

variable "database_security_group_id" {
  description = "RDS security group ID."
  type        = string
}

variable "container_port" {
  description = "Spring Boot container port."
  type        = number
  default     = 8080
}

variable "desired_count" {
  description = "Number of API tasks."
  type        = number
  default     = 1
}

variable "use_private_subnets" {
  description = "Run ECS tasks in the private subnets (requires enable_nat_gateway = true on the vpc module, or that every outbound call the API makes has a VPC endpoint) instead of the public subnets with a public IP. Defaults to false to match the current, working dev topology."
  type        = bool
  default     = false
}

variable "cognito_issuer_uri" {
  description = "OIDC issuer URI Spring Security validates JWTs against (app.security.cognito.issuer-uri), e.g. https://cognito-idp.<region>.amazonaws.com/<user-pool-id>."
  type        = string
}

variable "cognito_client_id" {
  description = "Cognito app client ID Spring Security validates the JWT client_id claim against (app.security.cognito.client-id)."
  type        = string
}

variable "certificate_arn" {
  description = "ACM certificate ARN for the ALB's HTTPS listener. When set, the ALB serves HTTPS on 443 and redirects HTTP (80) to HTTPS. When null, the ALB serves HTTP only (not recommended beyond initial bring-up before a certificate/domain exists)."
  type        = string
  default     = null
}

variable "alb_deletion_protection" {
  description = "Protect the load balancer from accidental deletion."
  type        = bool
  default     = false
}

variable "log_retention_days" {
  description = "CloudWatch Logs retention for the API log group."
  type        = number
  default     = 14
}

variable "additional_environment_variables" {
  description = "Extra plaintext container environment variables (e.g. APP_AI_ENABLED, APP_AI_PROVIDER). Merged with the built-in SPRING_PROFILES_ACTIVE/Cognito variables; keys here take precedence if duplicated."
  type        = map(string)
  default     = {}
}

variable "additional_secrets" {
  description = "Extra container secrets sourced from Secrets Manager, as { ENV_VAR_NAME = \"<secret-arn>:<json-key>::\" }. Use this for OPENAI_API_KEY. The execution role is automatically granted secretsmanager:GetSecretValue on every ARN referenced here (the ARN part before the first ':json-key' segment)."
  type        = map(string)
  default     = {}
}

variable "documents_bucket_arn" {
  description = "ARN of the S3 bucket containing archived documents. When set, the task role is granted s3:GetObject/s3:ListBucket on it, so the application can serve document downloads. Leave null if the API does not need document access."
  type        = string
  default     = null
}

variable "documents_bucket_name" {
  description = "Name (not ARN) of the same S3 bucket as documents_bucket_arn, passed through as the container's ARCHIVE_DOCUMENTS_BUCKET environment variable - S3AwardAttachmentStorage/S3SubawardAttachmentStorage read this at request time to resolve GetObject calls; granting IAM access via documents_bucket_arn alone is not sufficient, the application also needs the bucket *name* to know which bucket to call. Should be set whenever documents_bucket_arn is - leave null only if the API genuinely serves no document downloads at all."
  type        = string
  default     = null
}
