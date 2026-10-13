###############################################################################
# Customer-managed KMS keys, one per concern so that access can be granted and
# audited independently:
#   data    - RDS storage + Performance Insights, ElastiCache at rest, ECR images
#   secrets - Secrets Manager (application secrets + RDS-managed master secret)
#   logs    - CloudWatch Logs groups and the SNS alarm topic
###############################################################################

data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}
data "aws_region" "current" {}

locals {
  account_id = data.aws_caller_identity.current.account_id
  partition  = data.aws_partition.current.partition
  region     = data.aws_region.current.region
  root_arn   = "arn:${local.partition}:iam::${local.account_id}:root"
}

# Root-account statement: delegates key access to IAM policies (the standard AWS default key
# policy). Without it the key becomes unmanageable. Service principals get narrow extra statements.
data "aws_iam_policy_document" "root_admin" {
  #checkov:skip=CKV_AWS_109:KMS key policy - "Resource: *" means "this key"; root delegation is the AWS default key policy.
  #checkov:skip=CKV_AWS_111:KMS key policy - "Resource: *" means "this key"; write access is scoped by IAM policies.
  #checkov:skip=CKV_AWS_356:KMS key policy - "Resource: *" is the only valid value inside a key policy.
  statement {
    sid       = "EnableRootAccountIamDelegation"
    effect    = "Allow"
    actions   = ["kms:*"]
    resources = ["*"]
    principals {
      type        = "AWS"
      identifiers = [local.root_arn]
    }
  }
}

data "aws_iam_policy_document" "logs" {
  #checkov:skip=CKV_AWS_109:KMS key policy - "Resource: *" means "this key"; root delegation is the AWS default key policy.
  #checkov:skip=CKV_AWS_111:KMS key policy - "Resource: *" means "this key"; service statements are condition-scoped.
  #checkov:skip=CKV_AWS_356:KMS key policy - "Resource: *" is the only valid value inside a key policy.
  source_policy_documents = [data.aws_iam_policy_document.root_admin.json]

  statement {
    sid    = "AllowCloudWatchLogsForThisAccount"
    effect = "Allow"
    actions = [
      "kms:Encrypt*",
      "kms:Decrypt*",
      "kms:ReEncrypt*",
      "kms:GenerateDataKey*",
      "kms:Describe*",
    ]
    resources = ["*"]
    principals {
      type        = "Service"
      identifiers = ["logs.${local.region}.amazonaws.com"]
    }
    condition {
      test     = "ArnLike"
      variable = "kms:EncryptionContext:aws:logs:arn"
      values   = ["arn:${local.partition}:logs:${local.region}:${local.account_id}:log-group:*"]
    }
  }

  # CloudWatch alarms publish to the KMS-encrypted SNS topic.
  statement {
    sid       = "AllowCloudWatchAlarmsToUseSnsKey"
    effect    = "Allow"
    actions   = ["kms:Decrypt", "kms:GenerateDataKey*"]
    resources = ["*"]
    principals {
      type        = "Service"
      identifiers = ["cloudwatch.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [local.account_id]
    }
  }
}

resource "aws_kms_key" "data" {
  description             = "${var.name_prefix} data at rest (RDS, Performance Insights, ElastiCache, ECR)"
  enable_key_rotation     = true
  rotation_period_in_days = var.rotation_period_in_days
  deletion_window_in_days = var.deletion_window_in_days
  policy                  = data.aws_iam_policy_document.root_admin.json
  tags                    = merge(var.tags, { Name = "${var.name_prefix}-data" })
}

resource "aws_kms_alias" "data" {
  name          = "alias/${var.name_prefix}-data"
  target_key_id = aws_kms_key.data.key_id
}

resource "aws_kms_key" "secrets" {
  description             = "${var.name_prefix} Secrets Manager secrets"
  enable_key_rotation     = true
  rotation_period_in_days = var.rotation_period_in_days
  deletion_window_in_days = var.deletion_window_in_days
  policy                  = data.aws_iam_policy_document.root_admin.json
  tags                    = merge(var.tags, { Name = "${var.name_prefix}-secrets" })
}

resource "aws_kms_alias" "secrets" {
  name          = "alias/${var.name_prefix}-secrets"
  target_key_id = aws_kms_key.secrets.key_id
}

resource "aws_kms_key" "logs" {
  description             = "${var.name_prefix} CloudWatch Logs and SNS alarm topic"
  enable_key_rotation     = true
  rotation_period_in_days = var.rotation_period_in_days
  deletion_window_in_days = var.deletion_window_in_days
  policy                  = data.aws_iam_policy_document.logs.json
  tags                    = merge(var.tags, { Name = "${var.name_prefix}-logs" })
}

resource "aws_kms_alias" "logs" {
  name          = "alias/${var.name_prefix}-logs"
  target_key_id = aws_kms_key.logs.key_id
}
