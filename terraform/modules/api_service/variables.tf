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
