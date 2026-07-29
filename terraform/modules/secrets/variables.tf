variable "project_name" {
  description = "Project name used in resource names."
  type        = string
}

variable "environment" {
  description = "Environment name."
  type        = string
}

variable "secret_name" {
  description = "Name suffix for the OpenAI API key secret (created as <project>/<environment>/<secret_name>)."
  type        = string
  default     = "openai"
}

variable "recovery_window_in_days" {
  description = "Secrets Manager recovery window before a deleted secret is purged. Use 0 only in non-production environments."
  type        = number
  default     = 7
}
