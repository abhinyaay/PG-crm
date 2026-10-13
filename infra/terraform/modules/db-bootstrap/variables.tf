variable "name_prefix" {
  description = "Prefix for resource names, e.g. \"pgms-prod\"."
  type        = string
}

variable "environment" {
  description = "Environment name (log group path)."
  type        = string
}

variable "image" {
  description = "Image with psql 16, mirrored into ECR (e.g. <ops-repo-url>:postgres-16-alpine)."
  type        = string
}

variable "repository_arn" {
  description = "ARN of the ECR repository holding the image."
  type        = string
}

variable "cpu_architecture" {
  description = "CPU architecture of the mirrored image (X86_64 or ARM64)."
  type        = string
  default     = "X86_64"

  validation {
    condition     = contains(["X86_64", "ARM64"], var.cpu_architecture)
    error_message = "cpu_architecture must be X86_64 or ARM64."
  }
}

variable "db_host" {
  description = "RDS writer hostname."
  type        = string
}

variable "db_port" {
  description = "PostgreSQL port."
  type        = number
  default     = 5432
}

variable "db_name" {
  description = "Application database."
  type        = string
}

variable "db_master_username" {
  description = "RDS master username."
  type        = string
}

variable "app_db_username" {
  description = "Backend role name."
  type        = string
  default     = "pgms_app"
}

variable "analytics_db_username" {
  description = "Read-only analytics role name."
  type        = string
  default     = "analytics_ro"
}

variable "master_secret_arn" {
  description = "RDS-managed master user secret (JSON with username/password)."
  type        = string
}

variable "app_db_secret_arn" {
  description = "Secret with the pgms_app password."
  type        = string
}

variable "analytics_db_secret_arn" {
  description = "Secret with the analytics_ro password."
  type        = string
}

variable "secrets_kms_key_arn" {
  description = "KMS key encrypting the secrets."
  type        = string
}

variable "logs_kms_key_arn" {
  description = "KMS key encrypting the log group."
  type        = string
}

variable "log_retention_days" {
  description = "Retention of the task's log group."
  type        = number
  default     = 30
}

variable "tags" {
  description = "Tags applied to every resource in the module."
  type        = map(string)
  default     = {}
}
