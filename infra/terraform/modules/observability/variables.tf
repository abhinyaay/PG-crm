variable "name_prefix" {
  description = "Prefix for resource names, e.g. \"pgms-prod\"."
  type        = string
}

variable "environment" {
  description = "Environment name (log group path /pgms/<environment>/<service>)."
  type        = string
}

variable "account_id" {
  description = "AWS account ID (SNS topic policy source-account condition)."
  type        = string
}

variable "services" {
  description = "Services that get a log group."
  type        = list(string)
  default     = ["backend", "analytics", "frontend"]
}

variable "log_retention_days" {
  description = "CloudWatch retention of the service log groups."
  type        = number
  default     = 30

  validation {
    condition     = contains([1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731, 1096, 1827, 2192, 2557, 2922, 3288, 3653], var.log_retention_days)
    error_message = "log_retention_days must be a retention value supported by CloudWatch Logs."
  }
}

variable "logs_kms_key_arn" {
  description = "KMS key for log groups and the SNS topic."
  type        = string
}

variable "alarm_email_addresses" {
  description = "E-mail addresses subscribed to the alarm topic (each must confirm the subscription)."
  type        = list(string)
  default     = []

  validation {
    condition     = alltrue([for e in var.alarm_email_addresses : can(regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", e))])
    error_message = "alarm_email_addresses must contain valid e-mail addresses."
  }
}

variable "alb_arn_suffix" {
  description = "ARN suffix of the ALB (CloudWatch LoadBalancer dimension)."
  type        = string
}

variable "backend_target_group_arn_suffix" {
  description = "ARN suffix of the backend target group (CloudWatch TargetGroup dimension)."
  type        = string
}

variable "alb_5xx_rate_threshold_percent" {
  description = "Alarm when target 5xx responses exceed this percentage of requests for 5 minutes."
  type        = number
  default     = 1
}

variable "alb_5xx_min_requests_per_minute" {
  description = "Minimum requests per minute before the 5xx-rate alarm evaluates (avoids noise at low traffic)."
  type        = number
  default     = 20
}

variable "alb_p95_latency_threshold_seconds" {
  description = "Alarm when ALB TargetResponseTime p95 exceeds this many seconds for 5 minutes."
  type        = number
  default     = 1
}

variable "backend_min_healthy_hosts" {
  description = "Alarm when backend HealthyHostCount drops below this value."
  type        = number
  default     = 2
}

variable "db_writer_identifier" {
  description = "RDS writer identifier."
  type        = string
}

variable "db_replica_identifier" {
  description = "RDS read replica identifier (null if there is none)."
  type        = string
  default     = null
}

variable "db_max_connections" {
  description = "Configured max_connections (alarm at 80%)."
  type        = number
}

variable "db_allocated_storage_gib" {
  description = "Allocated storage in GiB (alarm when free space < 15%)."
  type        = number
}

variable "redis_member_cluster_ids" {
  description = "ElastiCache member cluster IDs to alarm on."
  type        = list(string)
}

variable "error_log_alarm_services" {
  description = "Services whose JSON logs are scanned for \"level\":\"ERROR\" lines."
  type        = list(string)
  default     = ["backend", "analytics"]
}

variable "error_log_threshold_per_5min" {
  description = "Alarm when a service logs more than this many ERROR lines in 5 minutes."
  type        = number
  default     = 10
}

variable "tags" {
  description = "Tags applied to every resource in the module."
  type        = map(string)
  default     = {}
}
