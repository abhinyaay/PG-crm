variable "name_prefix" {
  description = "Prefix for resource names, e.g. \"pgms-prod\"."
  type        = string
}

variable "vpc_id" {
  description = "VPC in which the security groups are created."
  type        = string
}

variable "endpoints_security_group_id" {
  description = "Security group of the interface VPC endpoints (from the network module)."
  type        = string
}

variable "s3_prefix_list_id" {
  description = "Prefix list ID of the S3 gateway endpoint (from the network module)."
  type        = string
}

variable "backend_port" {
  description = "Container port of the backend service."
  type        = number
  default     = 8080
}

variable "analytics_port" {
  description = "Container port of the analytics service."
  type        = number
  default     = 8000
}

variable "frontend_port" {
  description = "Container port of the frontend (nginx) service."
  type        = number
  default     = 80
}

variable "allow_https_egress_to_internet" {
  description = "Allow tcp/443 egress to 0.0.0.0/0 from task SGs (via NAT). Required if interface endpoints are disabled."
  type        = bool
  default     = false
}

variable "tags" {
  description = "Tags applied to every resource in the module."
  type        = map(string)
  default     = {}
}
