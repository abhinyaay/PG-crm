variable "account_id" {
  description = "AWS account ID."
  type        = string
}

variable "region" {
  description = "AWS region of the ECS resources."
  type        = string
}

variable "role_name" {
  description = "Name of the GitHub Actions deploy role."
  type        = string
}

variable "github_repository" {
  description = "GitHub repository allowed to assume the role, as \"<owner>/<repo>\"."
  type        = string

  validation {
    condition     = can(regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$", var.github_repository))
    error_message = "github_repository must look like \"owner/repo\"."
  }
}

variable "github_branch" {
  description = "Branch whose workflow runs may assume the role."
  type        = string
  default     = "main"
}

variable "github_environment" {
  description = "Protected GitHub environment used by the deploy job (null = branch subject only). Restrict its deployment branches to main in GitHub."
  type        = string
  default     = "production"
}

variable "create_oidc_provider" {
  description = "Create the GitHub OIDC identity provider (exactly one per AWS account)."
  type        = bool
  default     = true
}

variable "existing_oidc_provider_arn" {
  description = "ARN of an existing GitHub OIDC provider when create_oidc_provider = false."
  type        = string
  default     = null

  validation {
    condition     = var.create_oidc_provider || var.existing_oidc_provider_arn != null
    error_message = "existing_oidc_provider_arn is required when create_oidc_provider = false."
  }
}

variable "ecr_repository_arns" {
  description = "ECR repositories the pipeline may push to."
  type        = list(string)
}

variable "task_definition_families" {
  description = "Task definition families the pipeline registers revisions of."
  type        = list(string)
}

variable "ecs_service_arns" {
  description = "ECS services the pipeline may update."
  type        = list(string)
}

variable "image_tag_parameter_arns" {
  description = "SSM parameters the pipeline updates with the deployed image tag."
  type        = list(string)
}

variable "pass_role_arns" {
  description = "IAM roles the pipeline may pass to ECS (task roles and execution roles of the platform)."
  type        = list(string)
}

variable "tags" {
  description = "Tags applied to every resource in the module."
  type        = map(string)
  default     = {}
}
