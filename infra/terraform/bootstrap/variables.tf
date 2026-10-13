variable "region" {
  description = "Region of the state bucket and lock table (normally the deployment region)."
  type        = string
  default     = "ap-south-1"

  validation {
    condition     = can(regex("^[a-z]{2}(-[a-z]+)+-[0-9]$", var.region))
    error_message = "region must be an AWS region name such as ap-south-1."
  }
}

variable "project" {
  description = "Project name used in resource names."
  type        = string
  default     = "pgms"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,20}$", var.project))
    error_message = "project must be 2-21 lowercase alphanumeric characters or hyphens."
  }
}

variable "state_bucket_name" {
  description = "State bucket name. null = \"<project>-terraform-state-<account-id>\" (globally unique)."
  type        = string
  default     = null
}

variable "lock_table_name" {
  description = "DynamoDB lock table name."
  type        = string
  default     = "pgms-terraform-locks"
}

variable "noncurrent_version_retention_days" {
  description = "Days old state versions are kept (the newest 20 are always kept)."
  type        = number
  default     = 90
}
