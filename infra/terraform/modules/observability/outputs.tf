output "log_group_names" {
  description = "Map of service name to CloudWatch log group name."
  value       = { for k, g in aws_cloudwatch_log_group.service : k => g.name }
}

output "log_group_arns" {
  description = "Map of service name to CloudWatch log group ARN."
  value       = { for k, g in aws_cloudwatch_log_group.service : k => g.arn }
}

output "alarm_topic_arn" {
  description = "SNS topic that receives every alarm (subscribe Slack/PagerDuty here)."
  value       = aws_sns_topic.alarms.arn
}
