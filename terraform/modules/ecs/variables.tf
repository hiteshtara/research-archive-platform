variable "project_name" {
  type = string
}

variable "environment" {
  type = string
}

variable "aws_region" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "private_subnet_ids" {
  type = list(string)
}

variable "loader_image" {
  type = string
}

variable "data_bucket_arn" {
  type = string
}

variable "data_bucket_name" {
  type = string
}

variable "database_secret_arn" {
  type = string
}

variable "database_security_group_id" {
  type = string
}
