output "vpc_id" {
  description = "VPC ID."
  value       = aws_vpc.this.id
}

output "vpc_cidr" {
  description = "VPC CIDR block."
  value       = aws_vpc.this.cidr_block
}

output "public_subnet_ids" {
  description = "Public subnet IDs (ALB, NAT)."
  value       = aws_subnet.public[*].id
}

output "app_subnet_ids" {
  description = "Private application subnet IDs (ECS tasks)."
  value       = aws_subnet.app[*].id
}

output "data_subnet_ids" {
  description = "Private data subnet IDs (RDS, ElastiCache)."
  value       = aws_subnet.data[*].id
}

output "endpoints_security_group_id" {
  description = "Security group attached to the interface VPC endpoints."
  value       = aws_security_group.endpoints.id
}

output "s3_prefix_list_id" {
  description = "Prefix list of the S3 gateway endpoint (for task egress to ECR layer storage)."
  value       = aws_vpc_endpoint.s3.prefix_list_id
}

output "nat_public_ips" {
  description = "Elastic IPs of the NAT gateways (useful for allow-listing outbound traffic)."
  value       = aws_eip.nat[*].public_ip
}
