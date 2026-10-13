variable "secret_path_prefix" {
  description = "Path prefix of every secret name, e.g. \"pgms/prod\" (matches the task definition templates)."
  type        = string

  validation {
    condition     = can(regex("^[a-z0-9-]+(/[a-z0-9-]+)*$", var.secret_path_prefix))
    error_message = "secret_path_prefix must be slash-separated lowercase segments, e.g. pgms/prod."
  }
}

variable "kms_key_arn" {
  description = "KMS key used to encrypt the secrets."
  type        = string
}

variable "recovery_window_in_days" {
  description = "Days a deleted secret can be restored (0 = force delete, not recommended in prod)."
  type        = number
  default     = 30

  validation {
    condition     = var.recovery_window_in_days == 0 || (var.recovery_window_in_days >= 7 && var.recovery_window_in_days <= 30)
    error_message = "recovery_window_in_days must be 0 or between 7 and 30."
  }
}

variable "app_db_username" {
  description = "PostgreSQL role used by the backend."
  type        = string
  default     = "pgms_app"
}

variable "analytics_db_username" {
  description = "Read-only PostgreSQL role used by the analytics service."
  type        = string
  default     = "analytics_ro"
}

variable "db_name" {
  description = "Application database name."
  type        = string
}

variable "db_port" {
  description = "PostgreSQL port."
  type        = number
  default     = 5432
}

variable "db_writer_address" {
  description = "Hostname of the RDS writer."
  type        = string
}

variable "db_reader_address" {
  description = "Hostname of the RDS read replica (null = analytics reads from the writer)."
  type        = string
  default     = null
}

variable "redis_primary_endpoint" {
  description = "Primary endpoint address of the ElastiCache replication group."
  type        = string
}

variable "redis_port" {
  description = "Redis port."
  type        = number
  default     = 6379
}

variable "create_bootstrap_admin_secret" {
  description = "Create the bootstrap SUPER_ADMIN secret (only needed until the first admin exists)."
  type        = bool
  default     = true
}

variable "bootstrap_admin_email" {
  description = "E-mail address of the bootstrap SUPER_ADMIN account."
  type        = string
  default     = "admin@example.com"

  validation {
    condition     = can(regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", var.bootstrap_admin_email))
    error_message = "bootstrap_admin_email must be a valid e-mail address."
  }
}

variable "tags" {
  description = "Tags applied to every resource in the module."
  type        = map(string)
  default     = {}
}
