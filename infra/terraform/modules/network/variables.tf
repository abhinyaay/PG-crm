variable "name_prefix" {
  description = "Prefix for resource names, e.g. \"pgms-prod\"."
  type        = string
}

variable "environment" {
  description = "Environment name (used in log group paths)."
  type        = string
}

variable "vpc_cidr" {
  description = "CIDR block of the VPC."
  type        = string
  default     = "10.20.0.0/16"

  validation {
    condition     = can(cidrhost(var.vpc_cidr, 0)) && tonumber(split("/", var.vpc_cidr)[1]) <= 18
    error_message = "vpc_cidr must be a valid IPv4 CIDR of /18 or larger (three /19 tier blocks are carved from it)."
  }
}

variable "azs" {
  description = "Availability zones to use (2 or 3). One subnet per tier is created in each."
  type        = list(string)

  validation {
    condition     = length(var.azs) >= 2 && length(var.azs) <= 4
    error_message = "Provide between 2 and 4 availability zones."
  }
}

variable "public_subnet_cidrs" {
  description = "Optional explicit public subnet CIDRs (one per AZ). Empty = derived from vpc_cidr."
  type        = list(string)
  default     = []
}

variable "app_subnet_cidrs" {
  description = "Optional explicit private-app subnet CIDRs (one per AZ). Empty = derived from vpc_cidr."
  type        = list(string)
  default     = []
}

variable "data_subnet_cidrs" {
  description = "Optional explicit private-data subnet CIDRs (one per AZ). Empty = derived from vpc_cidr."
  type        = list(string)
  default     = []
}

variable "nat_gateway_mode" {
  description = "\"per_az\" (resilient, one NAT per AZ), \"single\" (cheaper, one shared NAT) or \"none\" (endpoints only; tasks cannot reach the internet)."
  type        = string
  default     = "per_az"

  validation {
    condition     = contains(["per_az", "single", "none"], var.nat_gateway_mode)
    error_message = "nat_gateway_mode must be one of: per_az, single, none."
  }
}

variable "enable_interface_endpoints" {
  description = "Create interface endpoints for ECR (api + dkr), Secrets Manager and CloudWatch Logs in every app subnet."
  type        = bool
  default     = true
}

variable "flow_log_retention_days" {
  description = "Retention of the VPC flow log group."
  type        = number
  default     = 90

  validation {
    condition     = contains([1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731, 1096, 1827, 2192, 2557, 2922, 3288, 3653], var.flow_log_retention_days)
    error_message = "flow_log_retention_days must be a retention value supported by CloudWatch Logs."
  }
}

variable "logs_kms_key_arn" {
  description = "KMS key ARN used to encrypt the flow log group."
  type        = string
}

variable "tags" {
  description = "Tags applied to every resource in the module."
  type        = map(string)
  default     = {}
}
