variable "project_name" {
  description = "Project name used in resource names."
  type        = string
}

variable "environment" {
  description = "Environment name."
  type        = string
}

variable "repository_url" {
  description = "HTTPS URL of the GitHub repository to deploy (e.g. https://github.com/bu-ist/research-archive-platform)."
  type        = string
}

variable "github_access_token" {
  description = "GitHub personal access token (repo scope) Amplify uses to connect to the repository. Never set this in a committed tfvars file - pass it via TF_VAR_github_access_token or a secret store."
  type        = string
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
