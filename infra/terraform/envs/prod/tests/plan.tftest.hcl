# Offline plan test: runs `terraform plan` for the whole prod composition against mocked AWS
# providers (no credentials, no API calls). Catches wiring errors that `validate` cannot, such as
# for_each keys unknown at plan time or invalid variable combinations.
#   terraform init -backend=false && terraform test

mock_provider "aws" {
  mock_data "aws_caller_identity" {
    defaults = {
      account_id = "123456789012"
    }
  }
  mock_data "aws_partition" {
    defaults = {
      partition = "aws"
    }
  }
  mock_data "aws_region" {
    defaults = {
      region = "ap-south-1"
    }
  }
  mock_data "aws_iam_policy_document" {
    defaults = {
      json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}"
    }
  }
  mock_data "aws_elb_service_account" {
    defaults = {
      arn = "arn:aws:iam::718504428378:root"
    }
  }
}

mock_provider "aws" {
  alias = "dr"
}

mock_provider "random" {}

variables {
  aws_account_id        = "123456789012"
  github_repository     = "example-org/pgms"
  domain_name           = "app.example.com"
  alarm_email_addresses = ["oncall@example.com"]
  image_tags = {
    backend   = "0123456789ab"
    analytics = "0123456789ab"
    frontend  = "0123456789ab"
  }
}

run "prod_defaults_plan" {
  command = plan

  assert {
    condition     = module.ecs.cluster_name == "pgms-prod"
    error_message = "Cluster must be named pgms-prod (AWS_DEPLOYMENT.md section 4)."
  }

  assert {
    condition     = toset(keys(module.ecr.repository_urls)) == toset(["pgms-backend", "pgms-analytics", "pgms-frontend", "pgms-ops"])
    error_message = "Expected the three application repositories plus the ops repository."
  }

  assert {
    condition     = module.ecs.service_names["backend"] == "pgms-backend" && module.ecs.service_names["analytics"] == "pgms-analytics"
    error_message = "Service names must match the CI defaults (pgms-backend, pgms-analytics)."
  }
}

run "cost_optimised_variant_plan" {
  command = plan

  variables {
    nat_gateway_mode            = "single"
    create_read_replica         = false
    analytics_use_fargate_spot  = true
    enable_cross_region_backups = true
    enable_bootstrap_admin      = false
    route53_zone_id             = "Z0123456789ABCDEFGHIJ"
  }
}

run "rejects_wrong_region_azs" {
  command = plan

  variables {
    azs = ["us-east-1a", "us-east-1b"]
  }

  expect_failures = [var.azs]
}
