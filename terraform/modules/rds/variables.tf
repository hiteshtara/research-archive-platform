variable "project_name" {
  description = "Project name used in resource names."
  type        = string
}

variable "environment" {
  description = "Environment name."
  type        = string
}

variable "vpc_id" {
  description = "VPC ID for RDS."
  type        = string
}

variable "private_subnet_ids" {
  description = "Private subnet IDs used by the DB subnet group."
  type        = list(string)
}

variable "allowed_cidr_blocks" {
  description = "CIDR blocks allowed to connect to PostgreSQL."
  type        = list(string)
  default     = []
}

variable "database_name" {
  description = "Initial PostgreSQL database name."
  type        = string
  default     = "research_archive"
}

variable "master_username" {
  description = "PostgreSQL master username."
  type        = string
  default     = "archive_admin"
}

variable "instance_class" {
  description = "RDS instance class."
  type        = string
  default     = "db.t4g.micro"
}

variable "allocated_storage" {
  description = "Initial allocated storage in GB."
  type        = number
  default     = 20
}

variable "max_allocated_storage" {
  description = "Maximum autoscaled storage in GB."
  type        = number
  default     = 100
}

variable "backup_retention_days" {
  description = "Automated backup retention."
  type        = number
  default     = 7
}

variable "deletion_protection" {
  description = "Protect the database from accidental deletion."
  type        = bool
  default     = false
}

variable "skip_final_snapshot" {
  description = "Skip final snapshot when destroying development DB."
  type        = bool
  default     = true
}

variable "multi_az" {
  description = "Run a synchronously-replicated standby in a second availability zone for automatic failover. Should be true in production; the extra cost is not usually justified in dev/test."
  type        = bool
  default     = false
}

variable "apply_immediately" {
  description = "Apply modifications immediately instead of waiting for the next maintenance window. Should be false in production, so disruptive changes (e.g. instance class resize) land at a predictable, low-traffic time instead of immediately during business hours."
  type        = bool
  default     = true
}

variable "maintenance_window" {
  description = "Preferred weekly maintenance window, e.g. \"sun:06:00-sun:07:00\" (UTC). Leave null to let AWS assign a random window - fine for dev/test, but production should pin this to a known low-traffic time so deferred (apply_immediately = false) changes are predictable."
  type        = string
  default     = null
}

variable "backup_window" {
  description = "Preferred daily automated-backup window, e.g. \"05:00-05:30\" (UTC). Must not overlap maintenance_window. Leave null to let AWS assign a random window."
  type        = string
  default     = null
}
