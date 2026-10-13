variable "name_prefix" {
  description = "Prefix for resource names, e.g. \"pgms-prod\"."
  type        = string
}

variable "environment" {
  description = "Environment name (used in log group paths)."
  type        = string
}

variable "data_subnet_ids" {
  description = "Private data subnets (at least two AZs)."
  type        = list(string)

  validation {
    condition     = length(var.data_subnet_ids) >= 2
    error_message = "At least two data subnets in different AZs are required for Multi-AZ."
  }
}

variable "rds_security_group_id" {
  description = "Security group for the RDS instances."
  type        = string
}

variable "redis_security_group_id" {
  description = "Security group for the ElastiCache replication group."
  type        = string
}

variable "data_kms_key_arn" {
  description = "KMS key for RDS storage, Performance Insights and ElastiCache at rest."
  type        = string
}

variable "secrets_kms_key_arn" {
  description = "KMS key for the RDS-managed master user secret."
  type        = string
}

variable "logs_kms_key_arn" {
  description = "KMS key for the RDS / Redis CloudWatch log groups."
  type        = string
}

variable "log_retention_days" {
  description = "Retention of the RDS and Redis log groups."
  type        = number
  default     = 30
}

variable "deletion_protection" {
  description = "Enable deletion protection on the RDS instances."
  type        = bool
  default     = true
}

# ---------------------------------------------------------------------------- PostgreSQL

variable "db_engine_version" {
  description = "PostgreSQL engine version. A major version (\"16\") lets RDS pick the default minor; minors auto-upgrade."
  type        = string
  default     = "16"

  validation {
    condition     = can(regex("^16(\\.[0-9]+)?$", var.db_engine_version))
    error_message = "This stack targets PostgreSQL 16 (parameter group family postgres16)."
  }
}

variable "db_instance_class" {
  description = "Instance class of the writer."
  type        = string
  default     = "db.r6g.large"

  validation {
    condition     = startswith(var.db_instance_class, "db.")
    error_message = "db_instance_class must be an RDS instance class, e.g. db.r6g.large."
  }
}

variable "db_replica_instance_class" {
  description = "Instance class of the analytics read replica."
  type        = string
  default     = "db.r6g.large"

  validation {
    condition     = startswith(var.db_replica_instance_class, "db.")
    error_message = "db_replica_instance_class must be an RDS instance class."
  }
}

variable "db_ca_cert_identifier" {
  description = "RDS CA used for the server certificate."
  type        = string
  default     = "rds-ca-rsa2048-g1"
}

variable "db_name" {
  description = "Name of the application database."
  type        = string
  default     = "pgms"
}

variable "db_master_username" {
  description = "Master (admin) username; used only for provisioning roles and extensions."
  type        = string
  default     = "pgms_admin"

  validation {
    condition     = can(regex("^[a-z][a-z0-9_]{0,62}$", var.db_master_username)) && !contains(["postgres", "admin", "rdsadmin"], var.db_master_username)
    error_message = "db_master_username must be a valid PostgreSQL identifier and not a reserved name."
  }
}

variable "db_allocated_storage" {
  description = "Initial gp3 storage in GiB."
  type        = number
  default     = 100

  validation {
    condition     = var.db_allocated_storage >= 20
    error_message = "db_allocated_storage must be at least 20 GiB."
  }
}

variable "db_max_allocated_storage" {
  description = "Upper bound for storage autoscaling in GiB."
  type        = number
  default     = 500
}

variable "db_multi_az" {
  description = "Run the writer Multi-AZ (synchronous standby)."
  type        = bool
  default     = true
}

variable "db_max_connections" {
  description = "max_connections parameter (static, applied on reboot)."
  type        = number
  default     = 300

  validation {
    condition     = var.db_max_connections >= 50 && var.db_max_connections <= 5000
    error_message = "db_max_connections must be between 50 and 5000."
  }
}

variable "db_extra_parameters" {
  description = "Additional PostgreSQL parameters (applied with pending-reboot). Must not repeat the module's built-in parameters."
  type        = map(string)
  default     = {}
}

variable "db_backup_retention_days" {
  description = "Automated backup / PITR retention in days."
  type        = number
  default     = 14

  validation {
    condition     = var.db_backup_retention_days >= 7 && var.db_backup_retention_days <= 35
    error_message = "db_backup_retention_days must be between 7 and 35 for production."
  }
}

variable "db_backup_window" {
  description = "Daily backup window (UTC)."
  type        = string
  default     = "19:00-20:00" # 00:30-01:30 IST
}

variable "db_maintenance_window" {
  description = "Weekly maintenance window (UTC); must not overlap the backup window."
  type        = string
  default     = "sun:20:30-sun:21:30" # Monday 02:00 IST
}

variable "db_performance_insights_retention_days" {
  description = "Performance Insights retention (7 = free tier, or a multiple of 31 up to 731)."
  type        = number
  default     = 7
}

variable "create_read_replica" {
  description = "Create the analytics read replica."
  type        = bool
  default     = true
}

variable "db_replica_multi_az" {
  description = "Run the read replica Multi-AZ."
  type        = bool
  default     = false
}

variable "enable_cross_region_backups" {
  description = "Replicate automated backups to the aws.dr provider region for disaster recovery."
  type        = bool
  default     = false
}

variable "dr_backup_retention_days" {
  description = "Retention of the replicated automated backups in the DR region."
  type        = number
  default     = 7
}

# ---------------------------------------------------------------------------- Redis

variable "redis_engine_version" {
  description = "ElastiCache Redis OSS engine version."
  type        = string
  default     = "7.1"

  validation {
    condition     = can(regex("^7\\.", var.redis_engine_version))
    error_message = "This stack targets Redis 7 (parameter group family redis7)."
  }
}

variable "redis_node_type" {
  description = "ElastiCache node type."
  type        = string
  default     = "cache.t4g.small"
}

variable "redis_num_cache_clusters" {
  description = "Number of nodes (1 primary + N-1 replicas in other AZs). Automatic failover and Multi-AZ are always on."
  type        = number
  default     = 2

  validation {
    condition     = var.redis_num_cache_clusters >= 2 && var.redis_num_cache_clusters <= 6
    error_message = "redis_num_cache_clusters must be between 2 and 6 (automatic failover needs a replica)."
  }
}

variable "redis_auth_token" {
  description = "AUTH token (16-128 printable characters, from the secrets module)."
  type        = string
  sensitive   = true

  validation {
    condition     = length(var.redis_auth_token) >= 16 && length(var.redis_auth_token) <= 128
    error_message = "redis_auth_token must be 16-128 characters."
  }
}

variable "redis_snapshot_retention_days" {
  description = "Days of daily Redis snapshots to keep (data is a cache, so short retention is fine)."
  type        = number
  default     = 3
}

variable "redis_snapshot_window" {
  description = "Daily snapshot window (UTC)."
  type        = string
  default     = "18:00-19:00"
}

variable "redis_maintenance_window" {
  description = "Weekly maintenance window (UTC)."
  type        = string
  default     = "sun:21:30-sun:22:30"
}

variable "tags" {
  description = "Tags applied to every resource in the module."
  type        = map(string)
  default     = {}
}
