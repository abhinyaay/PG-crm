variable "repository_names" {
  description = "ECR repositories to create."
  type        = list(string)
  default     = ["pgms-backend", "pgms-analytics", "pgms-frontend"]

  validation {
    condition     = alltrue([for n in var.repository_names : can(regex("^[a-z0-9][a-z0-9._/-]{1,255}$", n))])
    error_message = "Repository names must be lowercase and may contain . _ / -."
  }
}

variable "kms_key_arn" {
  description = "KMS key used to encrypt images at rest."
  type        = string
}

variable "keep_last_images" {
  description = "Number of images kept per repository."
  type        = number
  default     = 30

  validation {
    condition     = var.keep_last_images >= 5
    error_message = "Keep at least 5 images so rollbacks remain possible."
  }
}

variable "untagged_expiry_days" {
  description = "Days after which untagged images are expired."
  type        = number
  default     = 7

  validation {
    condition     = var.untagged_expiry_days >= 1
    error_message = "untagged_expiry_days must be at least 1."
  }
}

variable "tags" {
  description = "Tags applied to every resource in the module."
  type        = map(string)
  default     = {}
}
