terraform {
  required_version = ">= 1.9.0"

  # Bootstrap uses local state on purpose (it creates the remote backend). After the first apply
  # you may migrate it into the bucket it created: see ../README.md "Bootstrap remote state".

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 6.0, < 7.0"
    }
  }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project   = var.project
      ManagedBy = "terraform"
      Stack     = "bootstrap"
    }
  }
}
