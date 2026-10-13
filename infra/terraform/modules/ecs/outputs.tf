output "cluster_name" {
  description = "ECS cluster name."
  value       = aws_ecs_cluster.this.name
}

output "cluster_arn" {
  description = "ECS cluster ARN."
  value       = aws_ecs_cluster.this.arn
}

output "service_names" {
  description = "Map of container name to ECS service name."
  value       = { for k, s in aws_ecs_service.this : k => s.name }
}

output "service_arns" {
  description = "Map of container name to ECS service ARN."
  value       = { for k, s in aws_ecs_service.this : k => s.id }
}

output "task_definition_families" {
  description = "Map of container name to task definition family (CI reads the latest revision of it)."
  value       = { for k, t in aws_ecs_task_definition.this : k => t.family }
}

output "task_role_arns" {
  description = "Map of container name to task role ARN."
  value       = { for k, r in aws_iam_role.task : k => r.arn }
}

output "execution_role_arns" {
  description = "Map of container name to execution role ARN."
  value       = { for k, r in aws_iam_role.execution : k => r.arn }
}

output "image_tag_parameter_names" {
  description = "Map of container name to the SSM parameter the pipeline updates with the deployed tag."
  value       = { for k, p in aws_ssm_parameter.image_tag : k => p.name }
}

output "image_tag_parameter_arns" {
  description = "Map of container name to image-tag SSM parameter ARN."
  value       = { for k, p in aws_ssm_parameter.image_tag : k => p.arn }
}

output "service_connect_namespace_arn" {
  description = "ARN of the Service Connect namespace."
  value       = aws_service_discovery_http_namespace.this.arn
}
