output "data_key_arn" {
  description = "ARN of the KMS key for data at rest (RDS, Performance Insights, ElastiCache, ECR)."
  value       = aws_kms_key.data.arn
}

output "secrets_key_arn" {
  description = "ARN of the KMS key for Secrets Manager."
  value       = aws_kms_key.secrets.arn
}

output "logs_key_arn" {
  description = "ARN of the KMS key for CloudWatch Logs and SNS."
  value       = aws_kms_key.logs.arn
}
