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

# BU Oracle staging connectivity (etl/load_award_attachments.py --ecs,
# and any future direct-Oracle loader). See
# docs/ORACLE_STAGING_CONNECTIVITY.md for why peering alone isn't
# enough, why both directions of routing are required, and which side
# of each resource this project owns versus BU central IT.
variable "enable_oracle_peering" {
  description = "Peer this VPC with BU's Oracle staging VPC and add the private-side routes the loader needs to reach the staging Oracle RDS instance. The peering connection already exists (manually created) - see docs/ORACLE_STAGING_CONNECTIVITY.md for the terraform import command. Defaults to false so environments that don't need Oracle connectivity never have to set the oracle_* variables below."
  type        = bool
  default     = false
}

variable "oracle_vpc_id" {
  description = "BU Oracle staging VPC ID to peer with. Required when enable_oracle_peering is true."
  type        = string
  default     = null
}

variable "oracle_subnet_cidrs" {
  description = "Oracle staging VPC subnet CIDRs containing the Oracle RDS instance - one route is added to the private route table for each, via the peering connection. Required when enable_oracle_peering is true."
  type        = list(string)
  default     = []
}

variable "oracle_route_table_ids" {
  description = "BU-owned route table IDs on the Oracle staging VPC side. Only a single return route back to this VPC's CIDR is added to each - the route tables themselves (which carry many unrelated BU routes) are not managed by this project. Required when enable_oracle_peering is true."
  type        = list(string)
  default     = []
}
