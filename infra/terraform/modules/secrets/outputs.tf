output "secret_arns" {
  description = "Map of secret short name (db-app, redis, analytics, jwt, ...) to ARN."
  value       = { for k, s in aws_secretsmanager_secret.this : k => s.arn }
}

output "redis_auth_token" {
  description = "Generated ElastiCache AUTH token (consumed by the data module)."
  value       = random_password.redis_auth_token.result
  sensitive   = true
}
