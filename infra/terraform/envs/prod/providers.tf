locals {
  default_tags = merge(
    {
      Project     = var.project
      Environment = var.environment
      ManagedBy   = "terraform"
      Repository  = var.github_repository
    },
    var.tags,
  )
}

provider "aws" {
  region = var.region

  # Refuse to run against any other account (protects against a wrong AWS_PROFILE).
  allowed_account_ids = [var.aws_account_id]

  default_tags {
    tags = local.default_tags
  }
}

# Second region for cross-region automated backup replication (DR). Always configured because the
# data module declares it; no resources are created there unless enable_cross_region_backups = true.
provider "aws" {
  alias  = "dr"
  region = var.dr_region

  allowed_account_ids = [var.aws_account_id]

  default_tags {
    tags = local.default_tags
  }
}
