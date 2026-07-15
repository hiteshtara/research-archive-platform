variable "aws_region" {
  description = "AWS region used for development."
  type        = string
  default     = "us-east-1"
}

variable "project_name" {
  description = "Project name."
  type        = string
  default     = "research-archive-platform"
}

variable "environment" {
  description = "Environment name."
  type        = string
  default     = "dev"
}

variable "vpc_cidr" {
  description = "Development VPC CIDR."
  type        = string
}

variable "public_subnet_cidrs" {
  description = "Development public subnet CIDRs."
  type        = list(string)
}

variable "private_subnet_cidrs" {
  description = "Development private subnet CIDRs."
  type        = list(string)
}

variable "availability_zones" {
  description = "Development availability zones."
  type        = list(string)
}

variable "database_name" {
  description = "Archive database name."
  type        = string
  default     = "research_archive"
}

variable "database_instance_class" {
  description = "Development RDS instance class."
  type        = string
  default     = "db.t4g.micro"
}

variable "database_allowed_cidrs" {
  description = "CIDRs allowed to connect to PostgreSQL."
  type        = list(string)
  default     = []
}
