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

variable "attachment_viewer_usernames" {
  description = <<-EOT
    Cognito usernames (the pool's own generated sub/UUID username - see
    `aws cognito-idp list-users`, not the email alias, since this pool
    uses username_attributes = ["email"] which makes email an alias
    rather than the real username) to add to the ArchiveAttachmentViewer
    group. This group is the sole gate AttachmentAuthorizationService
    checks (server-side, via the cognito:groups JWT claim already mapped
    to a Spring authority) for every Award/Proposal/Subaward/Negotiation
    attachment endpoint and the cross-domain Archived File Finder search
    - see docs/architecture/NEGOTIATION_ATTACHMENT_ACCESS_DESIGN.md.
    Keep this list explicit and reviewed in a Terraform plan before
    apply - it is the actual attachment-access allowlist for this
    environment, not a default-open convenience.
    EOT
  type        = list(string)
  default     = []
}

variable "allow_admin_password_auth" {
  description = <<-EOT
    Adds ALLOW_ADMIN_USER_PASSWORD_AUTH to the app client's explicit_auth_flows,
    allowing a token to be obtained directly via `aws cognito-idp
    admin-initiate-auth` (email + password, no browser, no SRP client
    implementation needed) for an admin-created user.

    This exists solely for exercising the API/UI wiring against
    temporary, admin-created dev/test users before real end-user
    authentication (BU SAML federation, or a browser-based Hosted UI
    login) is in place - it is not itself an identity design. Leave
    false once real users authenticate through Hosted UI/federation, so
    this flow can't be used to bypass that.
    EOT
  type        = bool
  default     = false
}
