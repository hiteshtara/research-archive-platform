variable "project_name" {
  description = "Project name used in resource names."
  type        = string
}

variable "environment" {
  description = "Environment name."
  type        = string
}

variable "repository_url" {
  description = "HTTPS URL of the GitHub repository to deploy (e.g. https://github.com/bu-ist/research-archive-platform). Leave null (with github_access_token also null) to create the app with no repository connected yet."
  type        = string
  default     = null
}

variable "github_access_token" {
  description = <<-EOT
    A GitHub personal access token, used once by AWS to attach the
    Amplify GitHub App's webhook/deploy key to repository_url - AWS
    does not store it (confirmed against the provider's resource
    schema/docs), and it is excluded from ignore_changes for exactly
    that reason (tracking it would otherwise show a permanent phantom
    diff every plan, since reads always come back empty).

    Requires the Amplify GitHub App to already be installed against
    this repo in your GitHub account/org - a one-time, per-account/
    region, browser-based step with no Terraform equivalent:
    https://github.com/apps/aws-amplify-<region>/installations/new

    Scope this token to ONLY `admin:repo_hook` (GitHub Settings >
    Developer settings > Personal access tokens) - broader scopes like
    `repo` are not needed for this and should be avoided. Never set
    this in a committed tfvars file - pass it via
    TF_VAR_amplify_github_access_token instead, and consider revoking
    it on GitHub after the apply that attaches the repository succeeds.
    EOT
  type        = string
  default     = null
  sensitive   = true
}

variable "branch_name" {
  description = "Git branch Amplify builds and deploys."
  type        = string
  default     = "main"
}

variable "enable_auto_build" {
  description = "Automatically build and deploy on every push to branch_name."
  type        = bool
  default     = true
}

variable "environment_variables" {
  description = "Build-time environment variables for the UI (e.g. VITE_API_BASE_URL, VITE_AI_ENABLED)."
  type        = map(string)
  default     = {}
}

variable "custom_domain" {
  description = "Custom domain to attach to the Amplify app (e.g. archive.bu.edu). Leave null to use the default amplifyapp.com domain only."
  type        = string
  default     = null
}
