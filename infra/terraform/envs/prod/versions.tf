terraform {
  required_version = ">= 1.9.0"

  # Partial backend configuration: values come from backend.hcl (see backend.hcl.example), e.g.
  #   terraform init -backend-config=backend.hcl
  backend "s3" {}

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 6.0, < 7.0"
    }
  }
}
