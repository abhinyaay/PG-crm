output "alb_security_group_id" {
  description = "Security group of the public ALB."
  value       = aws_security_group.alb.id
}

output "backend_security_group_id" {
  description = "Security group of the backend tasks."
  value       = aws_security_group.backend.id
}

output "analytics_security_group_id" {
  description = "Security group of the analytics tasks."
  value       = aws_security_group.analytics.id
}

output "frontend_security_group_id" {
  description = "Security group of the frontend tasks."
  value       = aws_security_group.frontend.id
}

output "rds_security_group_id" {
  description = "Security group of the RDS instances."
  value       = aws_security_group.rds.id
}

output "redis_security_group_id" {
  description = "Security group of the ElastiCache replication group."
  value       = aws_security_group.redis.id
}
