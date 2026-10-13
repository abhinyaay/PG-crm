###############################################################################
# Application secrets (AWS_DEPLOYMENT.md section 8). Values are generated with
# random_password - nothing is hard-coded. Generated values live in the
# (encrypted, access-controlled) Terraform state; see README "Limitations".
#
#   <prefix>/db-app           {username, password}           -> backend DB_PASSWORD
#   <prefix>/db-analytics-ro  {username, password}           -> DBA uses it to create the role
#   <prefix>/redis            {auth_token}                   -> ElastiCache AUTH + backend REDIS_PASSWORD
#   <prefix>/analytics        {database_url, redis_url}      -> analytics DATABASE_URL / REDIS_URL
#   <prefix>/jwt              {private_key, public_key}      -> placeholder, filled out-of-band
#   <prefix>/bootstrap-admin  {email, password}              -> first deploy only (optional)
#
# The RDS master password is NOT here: RDS manages it in its own secret
# (manage_master_user_password) and rotates it.
###############################################################################

# Alphanumeric only: the values are embedded in connection URLs (postgresql://, rediss://) and
# ElastiCache forbids several special characters. 40-64 alphanumerics give > 230 bits of entropy.
resource "random_password" "db_app" {
  length  = 40
  special = false
}

resource "random_password" "db_analytics_ro" {
  length  = 40
  special = false
}

resource "random_password" "redis_auth_token" {
  length  = 64
  special = false
}

resource "random_password" "bootstrap_admin" {
  count = var.create_bootstrap_admin_secret ? 1 : 0

  length      = 24
  special     = false
  min_upper   = 2
  min_lower   = 2
  min_numeric = 2
}

locals {
  secrets = merge(
    {
      db-app          = "PostgreSQL credentials of the pgms_app role (backend, schema owner, runs Flyway)"
      db-analytics-ro = "PostgreSQL credentials of the analytics_ro role (read-only, analytics.* views)"
      redis           = "ElastiCache Redis AUTH token"
      analytics       = "Connection URLs for the analytics service (read replica + Redis)"
      jwt             = "RS256 key pair used by the backend to sign access tokens (filled out-of-band)"
    },
    var.create_bootstrap_admin_secret ? {
      bootstrap-admin = "First SUPER_ADMIN account - only needed for the first deploy"
    } : {}
  )

  analytics_db_host = coalesce(var.db_reader_address, var.db_writer_address)
}

resource "aws_secretsmanager_secret" "this" {
  #checkov:skip=CKV2_AWS_57:Rotation needs a rotation Lambda plus coordinated restarts (the analytics URL embeds the password); documented as follow-up in README. RDS master secret is rotated by RDS.
  for_each = local.secrets

  name                    = "${var.secret_path_prefix}/${each.key}"
  description             = each.value
  kms_key_id              = var.kms_key_arn
  recovery_window_in_days = var.recovery_window_in_days

  tags = var.tags
}

resource "aws_secretsmanager_secret_version" "db_app" {
  secret_id = aws_secretsmanager_secret.this["db-app"].id
  secret_string = jsonencode({
    username = var.app_db_username
    password = random_password.db_app.result
  })
}

resource "aws_secretsmanager_secret_version" "db_analytics_ro" {
  secret_id = aws_secretsmanager_secret.this["db-analytics-ro"].id
  secret_string = jsonencode({
    username = var.analytics_db_username
    password = random_password.db_analytics_ro.result
  })
}

resource "aws_secretsmanager_secret_version" "redis" {
  secret_id = aws_secretsmanager_secret.this["redis"].id
  secret_string = jsonencode({
    auth_token = random_password.redis_auth_token.result
  })
}

resource "aws_secretsmanager_secret_version" "analytics" {
  secret_id = aws_secretsmanager_secret.this["analytics"].id
  secret_string = jsonencode({
    database_url = "postgresql+psycopg://${var.analytics_db_username}:${random_password.db_analytics_ro.result}@${local.analytics_db_host}:${var.db_port}/${var.db_name}?sslmode=require"
    redis_url    = "rediss://:${random_password.redis_auth_token.result}@${var.redis_primary_endpoint}:${var.redis_port}/0"
  })
}

# Placeholder only. The real key pair is generated offline (scripts/generate-jwt-keys.sh) and written
# with `aws secretsmanager put-secret-value`, so the private key never enters Terraform state.
# The backend refuses to start until real keys are present (APP_JWT_ALLOW_EPHEMERAL_KEYS=false).
resource "aws_secretsmanager_secret_version" "jwt_placeholder" {
  secret_id = aws_secretsmanager_secret.this["jwt"].id
  secret_string = jsonencode({
    private_key = "REPLACE_ME"
    public_key  = "REPLACE_ME"
  })

  lifecycle {
    ignore_changes = [secret_string]
  }
}

resource "aws_secretsmanager_secret_version" "bootstrap_admin" {
  count = var.create_bootstrap_admin_secret ? 1 : 0

  secret_id = aws_secretsmanager_secret.this["bootstrap-admin"].id
  secret_string = jsonencode({
    email    = var.bootstrap_admin_email
    password = random_password.bootstrap_admin[0].result
  })
}
