variable "name_prefix" {
  description = "Prefix for resource names, e.g. \"pgms-prod\" (ALB name: max 32 characters)."
  type        = string

  validation {
    condition     = length(var.name_prefix) <= 22
    error_message = "name_prefix must be <= 22 characters so that target group names (<prefix>-analytics) fit in 32."
  }
}

variable "vpc_id" {
  description = "VPC of the target groups."
  type        = string
}

variable "public_subnet_ids" {
  description = "Public subnets for the ALB (one per AZ)."
  type        = list(string)
}

variable "alb_security_group_id" {
  description = "Security group of the ALB."
  type        = string
}

variable "domain_name" {
  description = "Public hostname served by the ALB (SPA + /api + /analytics on one origin), e.g. app.example.com."
  type        = string

  validation {
    condition     = can(regex("^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,}$", var.domain_name))
    error_message = "domain_name must be a lowercase fully qualified domain name."
  }
}

variable "subject_alternative_names" {
  description = "Additional hostnames on the certificate (and alias records when route53_zone_id is set)."
  type        = list(string)
  default     = []
}

variable "certificate_arn" {
  description = "Existing ACM certificate ARN. null = request a new DNS-validated certificate for domain_name."
  type        = string
  default     = null
}

variable "route53_zone_id" {
  description = "Hosted zone for DNS validation and the alias record. null = validate/point DNS manually."
  type        = string
  default     = null
}

variable "ssl_policy" {
  description = "TLS policy of the HTTPS listener."
  type        = string
  default     = "ELBSecurityPolicy-TLS13-1-2-2021-06"
}

variable "backend_port" {
  description = "Backend container port."
  type        = number
  default     = 8080
}

variable "analytics_port" {
  description = "Analytics container port."
  type        = number
  default     = 8000
}

variable "frontend_port" {
  description = "Frontend container port."
  type        = number
  default     = 80
}

variable "deregistration_delay_seconds" {
  description = "Target deregistration delay (graceful shutdown window)."
  type        = number
  default     = 30
}

variable "idle_timeout_seconds" {
  description = "ALB idle timeout."
  type        = number
  default     = 60
}

variable "expose_api_docs" {
  description = "Route /swagger-ui* and /v3/api-docs* to the backend (keep false in production)."
  type        = bool
  default     = false
}

variable "deletion_protection" {
  description = "Enable ALB deletion protection."
  type        = bool
  default     = true
}

variable "access_log_retention_days" {
  description = "Days ALB access logs are kept in S3."
  type        = number
  default     = 90

  validation {
    condition     = var.access_log_retention_days > 30
    error_message = "access_log_retention_days must be > 30 (logs transition to STANDARD_IA after 30 days)."
  }
}

variable "waf_rate_limit_per_5min" {
  description = "Max requests per client IP in a 5-minute window, all paths."
  type        = number
  default     = 3000

  validation {
    condition     = var.waf_rate_limit_per_5min >= 10
    error_message = "WAF rate limits must be >= 10."
  }
}

variable "waf_auth_rate_limit_per_5min" {
  description = "Max requests per client IP in a 5-minute window to /api/v1/auth/*."
  type        = number
  default     = 100

  validation {
    condition     = var.waf_auth_rate_limit_per_5min >= 10
    error_message = "WAF rate limits must be >= 10."
  }
}

variable "waf_common_rules_to_count" {
  description = "Rules of AWSManagedRulesCommonRuleSet switched to COUNT (false positives for this API)."
  type        = list(string)
  default     = ["SizeRestrictions_BODY"]
}

variable "waf_log_retention_days" {
  description = "Retention of the WAF log group."
  type        = number
  default     = 30
}

variable "logs_kms_key_arn" {
  description = "KMS key for the WAF log group."
  type        = string
}

variable "tags" {
  description = "Tags applied to every resource in the module."
  type        = map(string)
  default     = {}
}
