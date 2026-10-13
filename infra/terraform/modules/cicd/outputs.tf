output "deploy_role_arn" {
  description = "ARN of the GitHub Actions deploy role (store as the AWS_DEPLOY_ROLE_ARN secret)."
  value       = aws_iam_role.deploy.arn
}

output "oidc_provider_arn" {
  description = "ARN of the GitHub OIDC provider."
  value       = local.oidc_provider_arn
}
