output "task_definition_family" {
  description = "Family of the one-off db-bootstrap task definition."
  value       = aws_ecs_task_definition.this.family
}

output "log_group_name" {
  description = "Log group of the db-bootstrap task."
  value       = aws_cloudwatch_log_group.this.name
}
