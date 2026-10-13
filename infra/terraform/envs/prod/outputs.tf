output "alb_dns_name" {
  description = "ALB DNS name - point domain_name at it (alias/CNAME) if Route 53 is not managed here."
  value       = module.alb.alb_dns_name
}

output "app_url" {
  description = "Public URL of the platform."
  value       = local.public_origin
}

output "certificate_validation_records" {
  description = "ACM DNS validation records to create when route53_zone_id is null."
  value       = module.alb.certificate_validation_records
}

output "ecr_repository_urls" {
  description = "ECR repository URLs by repository name."
  value       = module.ecr.repository_urls
}

output "ecs_cluster_name" {
  description = "ECS cluster name."
  value       = module.ecs.cluster_name
}

output "ecs_service_names" {
  description = "ECS service names by container name."
  value       = module.ecs.service_names
}

output "task_definition_families" {
  description = "Task definition families by container name."
  value       = module.ecs.task_definition_families
}

output "image_tag_parameter_names" {
  description = "SSM parameters CI updates with the deployed image tag after each successful deploy."
  value       = module.ecs.image_tag_parameter_names
}

output "deploy_role_arn" {
  description = "GitHub Actions deploy role (AWS_DEPLOY_ROLE_ARN secret)."
  value       = module.cicd.deploy_role_arn
}

output "alarm_topic_arn" {
  description = "SNS topic for alarms."
  value       = module.observability.alarm_topic_arn
}

output "db_writer_address" {
  description = "RDS writer hostname."
  value       = module.data.db_writer_address
}

output "db_reader_address" {
  description = "RDS read replica hostname (null when disabled)."
  value       = module.data.db_reader_address
}

output "db_master_user_secret_arn" {
  description = "Secret holding the RDS master credentials (managed and rotated by RDS)."
  value       = module.data.db_master_user_secret_arn
}

output "redis_primary_endpoint" {
  description = "ElastiCache primary endpoint."
  value       = module.data.redis_primary_endpoint
}

output "db_bootstrap_run_task_command" {
  description = "Runs the one-off task that creates the application DB roles (first deployment, or to re-sync role passwords)."
  value = join(" ", [
    "aws ecs run-task",
    "--region ${var.region}",
    "--cluster ${module.ecs.cluster_name}",
    "--task-definition ${module.db_bootstrap.task_definition_family}",
    "--launch-type FARGATE",
    "--network-configuration 'awsvpcConfiguration={subnets=[${join(",", module.network.app_subnet_ids)}],securityGroups=[${module.security.backend_security_group_id}],assignPublicIp=DISABLED}'",
  ])
}

output "secret_arns" {
  description = "Application secret ARNs by short name."
  value       = module.secrets.secret_arns
}

output "github_actions_variables" {
  description = "Values for GitHub repository variables used by the deploy job (see README)."
  value = {
    AWS_REGION                = var.region
    ECS_CLUSTER               = module.ecs.cluster_name
    ECS_BACKEND_SERVICE       = module.ecs.service_names["backend"]
    ECS_ANALYTICS_SERVICE     = module.ecs.service_names["analytics"]
    ECS_FRONTEND_SERVICE      = module.ecs.service_names["frontend"]
    ECS_BACKEND_TASK_FAMILY   = module.ecs.task_definition_families["backend"]
    ECS_ANALYTICS_TASK_FAMILY = module.ecs.task_definition_families["analytics"]
    ECS_FRONTEND_TASK_FAMILY  = module.ecs.task_definition_families["frontend"]
    PUBLIC_BASE_URL           = local.public_origin
    IMAGE_TAG_PARAMETER_PATH  = "/${var.project}/${var.environment}/image-tag"
  }
}
