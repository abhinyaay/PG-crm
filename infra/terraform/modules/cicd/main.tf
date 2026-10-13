###############################################################################
# GitHub Actions -> AWS via OIDC (no long-lived keys). One deploy role that can
#   - push images to the platform's ECR repositories only,
#   - read/register task definitions and update the platform's ECS services,
#   - iam:PassRole only the platform's task + execution roles, only to ECS,
#   - record the deployed image tag in the platform's SSM image-tag parameters.
# Trust is limited to this repository's main branch and its protected
# "production" environment (the deploy job uses `environment: production`,
# which changes the OIDC subject claim to repo:<org>/<repo>:environment:production).
###############################################################################

data "aws_partition" "current" {}

locals {
  oidc_provider_arn = var.create_oidc_provider ? aws_iam_openid_connect_provider.github[0].arn : var.existing_oidc_provider_arn

  allowed_subjects = concat(
    ["repo:${var.github_repository}:ref:refs/heads/${var.github_branch}"],
    var.github_environment == null ? [] : ["repo:${var.github_repository}:environment:${var.github_environment}"],
  )
}

resource "aws_iam_openid_connect_provider" "github" {
  count = var.create_oidc_provider ? 1 : 0

  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
  # thumbprint_list omitted: AWS validates GitHub's OIDC endpoint against its trusted CA library.

  tags = var.tags
}

data "aws_iam_policy_document" "trust" {
  statement {
    sid     = "GitHubActionsOidc"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [local.oidc_provider_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = local.allowed_subjects
    }
  }
}

resource "aws_iam_role" "deploy" {
  name                 = var.role_name
  description          = "GitHub Actions deploy role for ${var.github_repository} (${var.github_branch})"
  assume_role_policy   = data.aws_iam_policy_document.trust.json
  max_session_duration = 3600
  tags                 = var.tags
}

data "aws_iam_policy_document" "deploy" {
  # ecr:GetAuthorizationToken, ecs:RegisterTaskDefinition and ecs:DescribeTaskDefinition do not support resource-level permissions.
  # ecs:RegisterTaskDefinition does not support resource-level permissions; what it can reference is bounded by the iam:PassRole statement.
  statement {
    sid       = "EcrLogin"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid = "EcrPushPlatformRepositories"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:BatchGetImage",
      "ecr:CompleteLayerUpload",
      "ecr:DescribeImageScanFindings",
      "ecr:DescribeImages",
      "ecr:DescribeRepositories",
      "ecr:GetDownloadUrlForLayer",
      "ecr:InitiateLayerUpload",
      "ecr:PutImage",
      "ecr:UploadLayerPart",
    ]
    resources = var.ecr_repository_arns
  }

  statement {
    sid       = "EcsTaskDefinitions"
    actions   = ["ecs:DescribeTaskDefinition", "ecs:RegisterTaskDefinition"]
    resources = ["*"]
  }

  statement {
    sid       = "EcsTagNewTaskDefinitions"
    actions   = ["ecs:TagResource"]
    resources = [for f in var.task_definition_families : "arn:${data.aws_partition.current.partition}:ecs:${var.region}:${var.account_id}:task-definition/${f}:*"]
    condition {
      test     = "StringEquals"
      variable = "ecs:CreateAction"
      values   = ["RegisterTaskDefinition"]
    }
  }

  statement {
    sid       = "EcsDeployPlatformServices"
    actions   = ["ecs:DescribeServices", "ecs:UpdateService"]
    resources = var.ecs_service_arns
  }

  # Record the deployed image tag so Terraform-registered revisions keep the running image.
  statement {
    sid       = "RecordDeployedImageTags"
    actions   = ["ssm:GetParameter", "ssm:PutParameter"]
    resources = var.image_tag_parameter_arns
  }

  # RegisterTaskDefinition passes BOTH roles referenced by the task definition: taskRoleArn and
  # executionRoleArn. Limiting PassRole to the task roles alone would make registration fail.
  statement {
    sid       = "PassPlatformTaskRolesToEcsOnly"
    actions   = ["iam:PassRole"]
    resources = var.pass_role_arns
    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role_policy" "deploy" {
  name   = "deploy"
  role   = aws_iam_role.deploy.id
  policy = data.aws_iam_policy_document.deploy.json
}
