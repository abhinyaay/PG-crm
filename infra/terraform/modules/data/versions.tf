terraform {
  required_version = ">= 1.9.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 6.0, < 7.0"
      # aws.dr = second region for cross-region automated backup replication (DR)
      configuration_aliases = [aws.dr]
    }
  }
}
