variable "name_prefix" {
  description = "Prefix for task families and IAM roles, e.g. \"pgms-prod\"."
  type        = string
}

variable "cluster_name" {
  description = "ECS cluster name (AWS_DEPLOYMENT.md: pgms-prod)."
  type        = string
}

variable "container_insights" {
  description = "Container Insights mode: \"enabled\" or \"enhanced\" (more granular, more expensive)."
  type        = string
  default     = "enabled"

  validation {
    condition     = contains(["enabled", "enhanced"], var.container_insights)
    error_message = "container_insights must be \"enabled\" or \"enhanced\"."
  }
}

variable "service_connect_namespace" {
  description = "Cloud Map HTTP namespace for Service Connect (backend is reachable as backend.<namespace>)."
  type        = string
  default     = "pgms.internal"
}

variable "image_tag_parameter_prefix" {
  description = "SSM parameter path prefix holding the deployed image tag per service, e.g. /pgms/prod/image-tag."
  type        = string

  validation {
    condition     = can(regex("^/[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)*$", var.image_tag_parameter_prefix))
    error_message = "image_tag_parameter_prefix must be an absolute SSM path without a trailing slash."
  }
}

variable "subnet_ids" {
  description = "Private app subnets for the tasks."
  type        = list(string)
}

variable "secrets_kms_key_arn" {
  description = "KMS key encrypting the Secrets Manager secrets (execution roles may decrypt via Secrets Manager only)."
  type        = string
}

variable "alb_arn_suffix" {
  description = "ALB ARN suffix (for ALBRequestCountPerTarget scaling)."
  type        = string
}

variable "enable_execute_command" {
  description = "Enable ECS Exec (break-glass shell into tasks via SSM). Adds ssmmessages permissions to task roles."
  type        = bool
  default     = false
}

variable "services" {
  description = <<-EOT
    Services keyed by container name. Fields:
      service_name              ECS service name (CI deploys to it)
      repository_url/arn        ECR repository
      image_tag                 initial tag seeding the SSM image-tag parameter (first apply only)
      cpu/memory                Fargate task size
      port                      container port (also the Service Connect / target group port)
      environment               plain environment variables
      secrets                   env var -> Secrets Manager valueFrom ("<arn>:<json-key>::")
      secret_arns               secret ARNs the execution role may read
      health_check              container health check (liveness)
      target_group_arn(_suffix) ALB target group (null = not load balanced)
      service_connect           null, or { discovery_name = null (client only) | "<name>" (server) }
      min/max_count             autoscaling bounds (min is also the initial desired count)
      cpu_target_percent        CPU target tracking value
      requests_per_target       ALBRequestCountPerTarget target (null = disabled)
  EOT
  type = map(object({
    service_name              = string
    repository_url            = string
    repository_arn            = string
    image_tag                 = string
    cpu                       = number
    memory                    = number
    cpu_architecture          = optional(string, "X86_64")
    port                      = number
    environment               = optional(map(string), {})
    secrets                   = optional(map(string), {})
    secret_arns               = optional(list(string), [])
    log_group_name            = string
    security_group_id         = string
    target_group_arn          = optional(string)
    target_group_arn_suffix   = optional(string)
    health_check_grace_period = optional(number, 60)
    stop_timeout              = optional(number, 30)
    readonly_root_filesystem  = optional(bool, false)
    system_controls           = optional(map(string), {})
    use_fargate_spot          = optional(bool, false)
    health_check = object({
      command      = list(string)
      interval     = optional(number, 30)
      timeout      = optional(number, 5)
      retries      = optional(number, 3)
      start_period = optional(number, 30)
    })
    service_connect = optional(object({
      discovery_name = optional(string)
    }))
    min_count           = number
    max_count           = number
    cpu_target_percent  = optional(number, 60)
    requests_per_target = optional(number)
  }))

  validation {
    condition     = alltrue([for s in values(var.services) : s.min_count >= 1 && s.max_count >= s.min_count])
    error_message = "Each service needs min_count >= 1 and max_count >= min_count."
  }

  validation {
    condition = alltrue([for s in values(var.services) : contains([
      "256/512", "256/1024", "256/2048",
      "512/1024", "512/2048", "512/3072", "512/4096",
      "1024/2048", "1024/3072", "1024/4096", "1024/5120", "1024/6144", "1024/7168", "1024/8192",
      "2048/4096", "2048/5120", "2048/6144", "2048/7168", "2048/8192", "2048/16384",
      "4096/8192", "4096/12288", "4096/16384", "4096/30720",
    ], "${s.cpu}/${s.memory}")])
    error_message = "cpu/memory must be a valid Fargate combination (e.g. 1024/2048, 512/1024)."
  }

  validation {
    condition     = alltrue([for s in values(var.services) : contains(["X86_64", "ARM64"], s.cpu_architecture)])
    error_message = "cpu_architecture must be X86_64 or ARM64."
  }

  validation {
    condition     = alltrue([for s in values(var.services) : s.cpu_target_percent > 10 && s.cpu_target_percent <= 90])
    error_message = "cpu_target_percent must be between 10 and 90."
  }
}

variable "tags" {
  description = "Tags applied to every resource in the module."
  type        = map(string)
  default     = {}
}
