###############################################################################
# One-off "db-bootstrap" Fargate task (run manually, never a service).
#
# RDS has no docker-entrypoint-initdb.d, so this task does on RDS what
# infra/postgres/init/01-roles.sh does locally - idempotently:
#   - pgms_app     LOGIN, owns schema public, CONNECT + CREATE on the database
#   - analytics_ro LOGIN, read-only by default (SELECT grants come from Flyway V2)
#   - extensions btree_gist + pg_trgm (need the admin role)
# Passwords are injected from Secrets Manager and passed as psql variables
# (quoted by psql, never interpolated into SQL text). Re-running it re-syncs the
# role passwords with Secrets Manager (e.g. after a manual secret change).
#
# Must run once BEFORE the backend starts for the first time (Flyway V2 grants
# to analytics_ro). The run-task command is in the root output.
###############################################################################

data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}
data "aws_region" "current" {}

locals {
  account_id = data.aws_caller_identity.current.account_id
  partition  = data.aws_partition.current.partition
  region     = data.aws_region.current.region
  family     = "${var.name_prefix}-db-bootstrap"

  script = <<-EOT
    set -eu
    psql -v ON_ERROR_STOP=1 \
         -v app_user="$APP_DB_USERNAME" -v app_pw="$APP_DB_PASSWORD" \
         -v ro_user="$ANALYTICS_DB_USERNAME" -v ro_pw="$ANALYTICS_DB_PASSWORD" \
         -v db="$PGDATABASE" -v admin="$PGUSER" <<'SQL'
    SELECT format('CREATE ROLE %I LOGIN', :'app_user')
     WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'app_user') \gexec
    SELECT format('CREATE ROLE %I LOGIN', :'ro_user')
     WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'ro_user') \gexec
    SELECT format('ALTER ROLE %I WITH LOGIN PASSWORD %L', :'app_user', :'app_pw') \gexec
    SELECT format('ALTER ROLE %I WITH LOGIN PASSWORD %L', :'ro_user', :'ro_pw') \gexec
    ALTER ROLE :"ro_user" SET default_transaction_read_only = on;

    -- PostgreSQL 16: the creating admin must be a member of the new owner to hand over the schema
    GRANT :"app_user" TO :"admin";

    REVOKE ALL ON DATABASE :"db" FROM PUBLIC;
    GRANT CONNECT, CREATE ON DATABASE :"db" TO :"app_user";
    GRANT CONNECT ON DATABASE :"db" TO :"ro_user";
    ALTER SCHEMA public OWNER TO :"app_user";

    CREATE EXTENSION IF NOT EXISTS btree_gist;
    CREATE EXTENSION IF NOT EXISTS pg_trgm;

    -- analytics_ro must not read base tables, only the views published in schema "analytics"
    REVOKE ALL ON SCHEMA public FROM PUBLIC;
    SQL
    echo "db-bootstrap: roles and extensions are in place"
  EOT
}

resource "aws_cloudwatch_log_group" "this" {
  # Retention is a variable (default 30 days, per AWS_DEPLOYMENT.md section 7).
  name              = "/pgms/${var.environment}/db-bootstrap"
  retention_in_days = var.log_retention_days
  kms_key_id        = var.logs_kms_key_arn
  tags              = var.tags
}

data "aws_iam_policy_document" "assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [local.account_id]
    }
    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values   = ["arn:${local.partition}:ecs:${local.region}:${local.account_id}:*"]
    }
  }
}

resource "aws_iam_role" "execution" {
  name               = "${local.family}-execution"
  description        = "ECS agent for the one-off db-bootstrap task"
  assume_role_policy = data.aws_iam_policy_document.assume.json
  tags               = var.tags
}

data "aws_iam_policy_document" "execution" {
  # ecr:GetAuthorizationToken does not support resource-level permissions.
  statement {
    sid       = "EcrAuth"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid       = "EcrPullOpsRepository"
    actions   = ["ecr:BatchCheckLayerAvailability", "ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer"]
    resources = [var.repository_arn]
  }

  statement {
    sid       = "WriteLogs"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${aws_cloudwatch_log_group.this.arn}:*"]
  }

  statement {
    sid       = "ReadDbSecrets"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [var.master_secret_arn, var.app_db_secret_arn, var.analytics_db_secret_arn]
  }

  statement {
    sid       = "DecryptSecretsViaSecretsManager"
    actions   = ["kms:Decrypt"]
    resources = [var.secrets_kms_key_arn]
    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["secretsmanager.${local.region}.amazonaws.com"]
    }
  }
}

resource "aws_iam_role_policy" "execution" {
  name   = "execution"
  role   = aws_iam_role.execution.id
  policy = data.aws_iam_policy_document.execution.json
}

resource "aws_iam_role" "task" {
  name               = "${local.family}-task"
  description        = "db-bootstrap task role (no AWS permissions)"
  assume_role_policy = data.aws_iam_policy_document.assume.json
  tags               = var.tags
}

resource "aws_ecs_task_definition" "this" {
  # psql writes ~/.psql_history; the task runs for seconds on demand and is never exposed.
  family                   = local.family
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = "256"
  memory                   = "512"
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = var.cpu_architecture
  }

  container_definitions = jsonencode([{
    name         = "db-bootstrap"
    image        = var.image
    essential    = true
    command      = ["sh", "-c", local.script]
    mountPoints  = []
    volumesFrom  = []
    portMappings = []

    environment = [
      { name = "ANALYTICS_DB_USERNAME", value = var.analytics_db_username },
      { name = "APP_DB_USERNAME", value = var.app_db_username },
      { name = "PGDATABASE", value = var.db_name },
      { name = "PGHOST", value = var.db_host },
      { name = "PGPORT", value = tostring(var.db_port) },
      { name = "PGSSLMODE", value = "require" },
      { name = "PGUSER", value = var.db_master_username },
    ]

    secrets = [
      { name = "ANALYTICS_DB_PASSWORD", valueFrom = "${var.analytics_db_secret_arn}:password::" },
      { name = "APP_DB_PASSWORD", valueFrom = "${var.app_db_secret_arn}:password::" },
      { name = "PGPASSWORD", valueFrom = "${var.master_secret_arn}:password::" },
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.this.name
        awslogs-region        = local.region
        awslogs-stream-prefix = "db-bootstrap"
      }
    }
  }])

  skip_destroy = true
  tags         = var.tags
}
