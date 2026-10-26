output "vpc_id" {
  description = "VPC ID."
  value       = aws_vpc.this.id
}

output "vpc_cidr" {
  description = "VPC CIDR."
  value       = aws_vpc.this.cidr_block
}

output "public_subnet_ids" {
  description = "Public subnet IDs."
  value       = aws_subnet.public[*].id
}

output "private_subnet_ids" {
  description = "Private subnet IDs."
  value       = aws_subnet.private[*].id
}

output "private_route_table_id" {
  description = "Private route table ID."
  value       = aws_route_table.private.id
}

output "nat_gateway_id" {
  description = "NAT Gateway ID, if enable_nat_gateway is true."
  value       = var.enable_nat_gateway ? aws_nat_gateway.this[0].id : null
}

output "oracle_peering_connection_id" {
  description = "VPC peering connection ID to the BU Oracle staging VPC, if enable_oracle_peering is true."
  value       = var.enable_oracle_peering ? aws_vpc_peering_connection.oracle_staging[0].id : null
}

output "vpc_endpoint_security_group_id" {
  description = "Security group shared by this VPC's interface endpoints. Already allows 443 from the VPC CIDR, so a new interface endpoint can reuse it instead of adding another SG."
  value       = aws_security_group.vpc_endpoints.id
}
