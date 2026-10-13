# ============================================================================ identity / placement

variable "aws_account_id" {
  description = "AWS account this stack may be applied to (provider allowed_account_ids guard)."
  type        = string

  validation {
    condition     = can(regex("^[0-9]{12}$", var.aws_account_id))
    error_message = "aws_account_id must be a 12-digit AWS account ID."
  }
}

variable "project" {
  description = "Project name used as a prefix for all resources."
  type        = string
  default     = "pgms"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,11}$", var.project))
    error_message = "project must be 2-12 lowercase alphanumeric characters or hyphens."
  }
}

variable "environment" {
  description = "Environment name (prod, staging, ...)."
  type        = string
  default     = "prod"

  validation {
    condition     = can(regex("^[a-z][a-z0-9]{1,9}$", var.environment))
    error_message = "environment must be 2-10 lowercase alphanumeric characters."
  }
}

variable "region" {
  description = "Primary AWS region."
  type        = string
  default     = "ap-south-1"

  validation {
    condition     = can(regex("^[a-z]{2}(-[a-z]+)+-[0-9]$", var.region))
    error_message = "region must be an AWS region name such as ap-south-1."
  }
}

variable "dr_region" {
  description = "Region receiving replicated RDS automated backups when enable_cross_region_backups = true."
  type        = string
  default     = "ap-southeast-1"

  validation {
    condition     = can(regex("^[a-z]{2}(-[a-z]+)+-[0-9]$", var.dr_region)) && var.dr_region != var.region
    error_message = "dr_region must be a valid AWS region different from region."
  }
}

variable "azs" {
  description = "Availability zones, pinned explicitly so the subnet layout never shifts when AWS adds a zone."
  type        = list(string)
  default     = ["ap-south-1a", "ap-south-1b", "ap-south-1c"]

  validation {
    condition     = length(var.azs) >= 2 && length(var.azs) <= 4 && alltrue([for az in var.azs : startswith(az, var.region)])
    error_message = "Provide 2-4 availability zones that belong to var.region."
  }
}

variable "tags" {
  description = "Extra tags merged into the provider default_tags."
  type        = map(string)
  default     = {}
}

# ============================================================================ network

variable "vpc_cidr" {
  description = "VPC CIDR (AWS_DEPLOYMENT.md: 10.20.0.0/16)."
  type        = string
  default     = "10.20.0.0/16"
}

variable "nat_gateway_mode" {
  description = "\"per_az\" (doc default, resilient), \"single\" (cheaper) or \"none\" (VPC endpoints only)."
  type        = string
  default     = "per_az"

  validation {
    condition     = contains(["per_az", "single", "none"], var.nat_gateway_mode)
    error_message = "nat_gateway_mode must be per_az, single or none."
  }
}

variable "enable_interface_endpoints" {
  description = "Interface endpoints for ECR, Secrets Manager and CloudWatch Logs (if false, tasks reach AWS APIs through NAT)."
  type        = bool
  default     = true

  validation {
    condition     = var.enable_interface_endpoints || var.nat_gateway_mode != "none"
    error_message = "With nat_gateway_mode = \"none\" the interface endpoints are the only path to ECR/Secrets Manager/Logs."
  }
}

variable "allow_https_egress_to_internet" {
  description = "Allow tasks tcp/443 to the internet via NAT (third-party APIs). Forced on when interface endpoints are disabled."
  type        = bool
  default     = false
}

# ============================================================================ edge

variable "domain_name" {
  description = "Public hostname of the platform, e.g. app.example.com (SPA, /api and /analytics share one origin)."
  type        = string

  validation {
    condition     = can(regex("^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,}$", var.domain_name))
    error_message = "domain_name must be a lowercase fully qualified domain name."
  }
}

variable "subject_alternative_names" {
  description = "Extra hostnames for the certificate."
  type        = list(string)
  default     = []
}

variable "certificate_arn" {
  description = "Existing ACM certificate in the region (null = request one for domain_name)."
  type        = string
  default     = null

  validation {
    condition     = var.certificate_arn == null || can(regex("^arn:aws[a-z-]*:acm:", var.certificate_arn))
    error_message = "certificate_arn must be an ACM certificate ARN."
  }
}

variable "route53_zone_id" {
  description = "Route 53 hosted zone for certificate validation and the alias record (null = manage DNS manually)."
  type        = string
  default     = null
}

variable "extra_cors_origins" {
  description = "Additional browser origins allowed by the backend CORS policy."
  type        = list(string)
  default     = []
}

variable "waf_rate_limit_per_5min" {
  description = "WAF per-IP request limit per 5 minutes (all paths)."
  type        = number
  default     = 3000
}

variable "waf_auth_rate_limit_per_5min" {
  description = "WAF per-IP request limit per 5 minutes for /api/v1/auth/*."
  type        = number
  default     = 100
}

# ============================================================================ compute

variable "image_tags" {
  description = "Initial image tags (12-char commit SHA as produced by CI) seeding the SSM image-tag parameters on the first apply. Afterwards CI records the deployed tag there and these values are ignored."
  type = object({
    backend   = string
    analytics = string
    frontend  = string
  })

  validation {
    condition     = alltrue([for t in values(var.image_tags) : can(regex("^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$", t))])
    error_message = "image_tags must be valid Docker tags."
  }
}

variable "container_insights" {
  description = "Container Insights mode (\"enabled\" or \"enhanced\")."
  type        = string
  default     = "enabled"
}

variable "service_connect_namespace" {
  description = "Service Connect namespace; analytics fetches JWKS from backend.<namespace>:8080."
  type        = string
  default     = "pgms.internal"
}

variable "backend_min_count" {
  description = "Minimum backend tasks (doc: 2, one per AZ)."
  type        = number
  default     = 2

  validation {
    condition     = var.backend_min_count >= 2
    error_message = "Run at least 2 backend tasks in production (one per AZ)."
  }
}

variable "backend_max_count" {
  description = "Maximum backend tasks (doc: 10)."
  type        = number
  default     = 10
}

variable "backend_requests_per_target" {
  description = "ALBRequestCountPerTarget (per minute) target for the backend."
  type        = number
  default     = 1000
}

variable "analytics_min_count" {
  description = "Minimum analytics tasks (doc: 1)."
  type        = number
  default     = 1
}

variable "analytics_max_count" {
  description = "Maximum analytics tasks (doc: 3)."
  type        = number
  default     = 3
}

variable "analytics_requests_per_target" {
  description = "ALBRequestCountPerTarget (per minute) target for analytics."
  type        = number
  default     = 300
}

variable "analytics_use_fargate_spot" {
  description = "Run analytics on Fargate Spot (cost lever from the doc; tasks can be interrupted)."
  type        = bool
  default     = false
}

variable "frontend_min_count" {
  description = "Minimum frontend tasks."
  type        = number
  default     = 2
}

variable "frontend_max_count" {
  description = "Maximum frontend tasks."
  type        = number
  default     = 4
}

variable "frontend_requests_per_target" {
  description = "ALBRequestCountPerTarget (per minute) target for the frontend."
  type        = number
  default     = 3000
}

variable "frontend_unprivileged_port_sysctl" {
  description = "Set net.ipv4.ip_unprivileged_port_start=0 so the non-root nginx can bind :80 on Fargate."
  type        = bool
  default     = true
}

variable "enable_execute_command" {
  description = "Enable ECS Exec (break-glass, e.g. DB port forwarding). Keep false unless needed."
  type        = bool
  default     = false
}

variable "app_timezone" {
  description = "Business timezone (APP_TIMEZONE / BUSINESS_TIMEZONE)."
  type        = string
  default     = "Asia/Kolkata"
}

variable "jwt_issuer" {
  description = "JWT issuer expected by analytics."
  type        = string
  default     = "pgms-auth"
}

variable "jwt_audience" {
  description = "JWT audience expected by analytics."
  type        = string
  default     = "pgms-api"
}

variable "db_bootstrap_image_tag" {
  description = "Tag of the psql image mirrored into the <project>-ops ECR repository for the db-bootstrap task."
  type        = string
  default     = "postgres-16-alpine"
}

variable "enable_bootstrap_admin" {
  description = "Create the bootstrap SUPER_ADMIN secret and inject it into the backend (first deploy only)."
  type        = bool
  default     = true
}

variable "bootstrap_admin_email" {
  description = "E-mail of the bootstrap SUPER_ADMIN."
  type        = string
  default     = "admin@example.com"
}

# ============================================================================ data

variable "db_name" {
  description = "Application database name."
  type        = string
  default     = "pgms"
}

variable "db_instance_class" {
  description = "RDS writer instance class (doc: db.r6g.large)."
  type        = string
  default     = "db.r6g.large"
}

variable "db_replica_instance_class" {
  description = "RDS read replica instance class."
  type        = string
  default     = "db.r6g.large"
}

variable "create_read_replica" {
  description = "Create the analytics read replica (false = analytics reads from the writer)."
  type        = bool
  default     = true
}

variable "db_allocated_storage" {
  description = "Initial RDS storage (GiB)."
  type        = number
  default     = 100
}

variable "db_max_allocated_storage" {
  description = "RDS storage autoscaling ceiling (GiB)."
  type        = number
  default     = 500

  validation {
    condition     = var.db_max_allocated_storage >= var.db_allocated_storage
    error_message = "db_max_allocated_storage must be >= db_allocated_storage."
  }
}

variable "db_pool_size" {
  description = "Hikari pool size per backend task (DB_POOL_SIZE)."
  type        = number
  default     = 15
}

variable "db_max_connections" {
  description = "Override for max_connections (null = backend_max_count x db_pool_size x 2 + 50)."
  type        = number
  default     = null
}

variable "enable_cross_region_backups" {
  description = "Replicate RDS automated backups to dr_region (doc section 5: snapshot copy for DR)."
  type        = bool
  default     = false
}

variable "redis_node_type" {
  description = "ElastiCache node type (doc: cache.t4g.small)."
  type        = string
  default     = "cache.t4g.small"
}

variable "redis_num_cache_clusters" {
  description = "Redis nodes (doc: 2 = primary + replica)."
  type        = number
  default     = 2
}

variable "deletion_protection" {
  description = "Deletion protection on RDS and the ALB."
  type        = bool
  default     = true
}

# ============================================================================ observability

variable "log_retention_days" {
  description = "CloudWatch Logs retention (doc: 30 days, longer retention via S3 export)."
  type        = number
  default     = 30
}

variable "alarm_email_addresses" {
  description = "E-mail subscribers of the alarm SNS topic."
  type        = list(string)
  default     = []
}

# ============================================================================ CI/CD

variable "github_repository" {
  description = "GitHub repository (\"owner/repo\") whose main branch may deploy."
  type        = string

  validation {
    condition     = can(regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$", var.github_repository))
    error_message = "github_repository must look like \"owner/repo\"."
  }
}

variable "github_branch" {
  description = "Branch allowed to assume the deploy role."
  type        = string
  default     = "main"
}

variable "github_environment" {
  description = "Protected GitHub environment of the deploy job (null to disallow environment subjects)."
  type        = string
  default     = "production"
}

variable "create_github_oidc_provider" {
  description = "Create the account-wide GitHub OIDC provider (false if it already exists)."
  type        = bool
  default     = true
}

variable "existing_github_oidc_provider_arn" {
  description = "Existing GitHub OIDC provider ARN when create_github_oidc_provider = false."
  type        = string
  default     = null
}
