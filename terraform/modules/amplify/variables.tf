variable "project_name" {
  description = "Project name used in resource names."
  type        = string
}

variable "environment" {
  description = "Environment name."
  type        = string
}

variable "repository_url" {
  description = "HTTPS URL of the GitHub repository to deploy (e.g. https://github.com/bu-ist/research-archive-platform). Leave null (with github_access_token also null) to create the app with no repository connected, then connect it manually via the AWS Console's GitHub App-based flow after the first apply - recommended, since it never requires a token that would persist in Terraform state."
  type        = string
  default     = null
}

variable "github_access_token" {
  description = "GitHub personal access token (repo scope) Amplify uses to connect to the repository. Only needed if you choose the legacy PAT-based connection instead of the recommended Console GitHub App flow (see repository_url). Never set this in a committed tfvars file - pass it via TF_VAR_github_access_token or a secret store. Note this still ends up stored in Terraform state - the AWS provider's aws_amplify_app resource has no write-only or CodeConnections-based alternative as of provider ~> 6.0 (verified against the provider schema)."
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
