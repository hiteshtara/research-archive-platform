variable "project_name" {
  description = "Project name used in AWS resource names."
  type        = string
}

variable "environment" {
  description = "Environment name."
  type        = string
}

variable "vpc_cidr" {
  description = "CIDR block for the VPC."
  type        = string
}

variable "public_subnet_cidrs" {
  description = "CIDRs for public subnets."
  type        = list(string)
}

variable "private_subnet_cidrs" {
  description = "CIDRs for private subnets."
  type        = list(string)
}

variable "availability_zones" {
  description = "Availability zones used by the subnets."
  type        = list(string)

  validation {
    condition = (
      length(var.availability_zones) >= 2 &&
      length(var.public_subnet_cidrs) == length(var.availability_zones) &&
      length(var.private_subnet_cidrs) == length(var.availability_zones)
    )

    error_message = "Provide at least two availability zones and one public and private subnet CIDR for each availability zone."
  }
}

variable "aws_region" {
  description = "AWS region used for VPC endpoint service names."
  type        = string
}

variable "enable_nat_gateway" {
  description = "Create a NAT Gateway and route private-subnet egress through it. Required if any workload runs in the private subnets and needs general internet access (e.g. calling third-party APIs) beyond the AWS services already reachable via VPC endpoints. Adds an hourly + per-GB cost."
  type        = bool
  default     = false
}
