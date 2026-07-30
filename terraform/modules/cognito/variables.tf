variable "project_name" {
  description = "Project name used in resource names."
  type        = string
}

variable "environment" {
  description = "Environment name."
  type        = string
}

variable "aws_region" {
  description = "AWS region, used to construct the issuer URI output."
  type        = string
}

variable "callback_urls" {
  description = "Allowed OAuth callback (sign-in redirect) URLs for the app client, e.g. the Amplify UI URL and http://localhost:5173/."
  type        = list(string)

  validation {
    condition     = length(var.callback_urls) > 0
    error_message = "Provide at least one callback URL."
  }
}

variable "logout_urls" {
  description = "Allowed OAuth logout (sign-out redirect) URLs for the app client."
  type        = list(string)

  validation {
    condition     = length(var.logout_urls) > 0
    error_message = "Provide at least one logout URL."
  }
}

variable "hosted_ui_domain_prefix" {
  description = "Domain prefix for the Cognito Hosted UI (must be globally unique across all Cognito users in the region). Leave null to skip creating a hosted UI domain."
  type        = string
  default     = null
}

variable "advanced_security_mode" {
  description = "Cognito advanced (threat-protection) security mode: OFF, AUDIT, or ENFORCED."
  type        = string
  default     = "AUDIT"

  validation {
    condition     = contains(["OFF", "AUDIT", "ENFORCED"], var.advanced_security_mode)
    error_message = "advanced_security_mode must be one of: OFF, AUDIT, ENFORCED."
  }
}

variable "deletion_protection" {
  description = "Protect the User Pool from accidental deletion via the Cognito API/console (independent of, and in addition to, this module's own prevent_destroy lifecycle block). ACTIVE or INACTIVE."
  type        = string
  default     = "INACTIVE"

  validation {
    condition     = contains(["ACTIVE", "INACTIVE"], var.deletion_protection)
    error_message = "deletion_protection must be one of: ACTIVE, INACTIVE."
  }
}

variable "mfa_configuration" {
  description = "Require multi-factor authentication: OFF, ON (required for every user), or OPTIONAL (user can enable it). Only TOTP (software token) MFA is configured here - no SMS/Pinpoint setup."
  type        = string
  default     = "OFF"

  validation {
    condition     = contains(["OFF", "ON", "OPTIONAL"], var.mfa_configuration)
    error_message = "mfa_configuration must be one of: OFF, ON, OPTIONAL."
  }
}
