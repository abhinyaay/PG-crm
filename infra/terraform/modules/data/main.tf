###############################################################################
# Data tier (AWS_DEPLOYMENT.md sections 5 and 6)
#   - RDS PostgreSQL 16, Multi-AZ writer, KMS-encrypted, force_ssl,
#     pg_stat_statements, 14-day PITR, Performance Insights, Enhanced Monitoring
#   - Read replica for the analytics service
#   - Optional cross-region automated-backup replication for DR
#   - ElastiCache Redis 7 replication group: TLS, AUTH, automatic failover,
#     volatile-lru
###############################################################################

data "aws_partition" "current" {}

locals {
  db_identifier         = "${var.name_prefix}-postgres"
  replica_identifier    = "${var.name_prefix}-postgres-replica"
  redis_id              = "${var.name_prefix}-redis"
  db_log_exports        = ["postgresql", "upgrade"]
  db_instances_with_log = var.create_read_replica ? [local.db_identifier, local.replica_identifier] : [local.db_identifier]

  db_log_groups = merge([
    for id in local.db_instances_with_log : {
      for t in local.db_log_exports : "${id}/${t}" => "/aws/rds/instance/${id}/${t}"
    }
  ]...)
}

# ============================================================================ RDS

resource "aws_db_subnet_group" "this" {
  name        = "${var.name_prefix}-postgres"
  description = "Private data subnets for ${var.name_prefix} PostgreSQL"
  subnet_ids  = var.data_subnet_ids
  tags        = var.tags
}

resource "aws_db_parameter_group" "this" {
  name        = "${var.name_prefix}-postgres16"
  family      = "postgres16"
  description = "${var.name_prefix} PostgreSQL 16 - force_ssl, pg_stat_statements, slow query logging"

  # TLS required for every connection (JDBC uses sslmode=require)
  parameter {
    name         = "rds.force_ssl"
    value        = "1"
    apply_method = "immediate"
  }

  # query statistics; static parameter -> applied at the next (maintenance-window) reboot
  parameter {
    name         = "shared_preload_libraries"
    value        = "pg_stat_statements"
    apply_method = "pending-reboot"
  }

  parameter {
    name         = "pg_stat_statements.track"
    value        = "top"
    apply_method = "immediate"
  }

  parameter {
    name         = "log_min_duration_statement"
    value        = "500"
    apply_method = "immediate"
  }

  # audit trail of schema changes (Flyway migrations, manual DDL)
  parameter {
    name         = "log_statement"
    value        = "ddl"
    apply_method = "immediate"
  }

  parameter {
    name         = "log_lock_waits"
    value        = "1"
    apply_method = "immediate"
  }

  parameter {
    name         = "log_connections"
    value        = "1"
    apply_method = "immediate"
  }

  parameter {
    name         = "log_disconnections"
    value        = "1"
    apply_method = "immediate"
  }

  # sized for max backend tasks x Hikari pool + headroom (computed by the caller); static
  parameter {
    name         = "max_connections"
    value        = tostring(var.db_max_connections)
    apply_method = "pending-reboot"
  }

  dynamic "parameter" {
    for_each = var.db_extra_parameters
    content {
      name         = parameter.key
      value        = parameter.value
      apply_method = "pending-reboot"
    }
  }

  tags = var.tags

  lifecycle {
    create_before_destroy = true
  }
}

# Pre-create the log groups RDS exports to, so they get a retention policy and KMS encryption
# (RDS would otherwise create them with infinite retention).
resource "aws_cloudwatch_log_group" "rds" {
  # Retention is a variable (default 30 days, per AWS_DEPLOYMENT.md section 7); long-term retention is via S3 export.
  for_each = local.db_log_groups

  name              = each.value
  retention_in_days = var.log_retention_days
  kms_key_id        = var.logs_kms_key_arn
  tags              = var.tags
}

data "aws_iam_policy_document" "rds_monitoring_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["monitoring.rds.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "rds_monitoring" {
  name               = "${var.name_prefix}-rds-enhanced-monitoring"
  assume_role_policy = data.aws_iam_policy_document.rds_monitoring_assume.json
  tags               = var.tags
}

resource "aws_iam_role_policy_attachment" "rds_monitoring" {
  role       = aws_iam_role.rds_monitoring.name
  policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/service-role/AmazonRDSEnhancedMonitoringRole"
}

resource "aws_db_instance" "writer" {
  identifier = local.db_identifier

  engine                     = "postgres"
  engine_version             = var.db_engine_version
  auto_minor_version_upgrade = true
  instance_class             = var.db_instance_class
  ca_cert_identifier         = var.db_ca_cert_identifier

  db_name  = var.db_name
  username = var.db_master_username
  port     = 5432
  # Master password generated, stored and rotated by RDS in Secrets Manager (never in TF state).
  manage_master_user_password   = true
  master_user_secret_kms_key_id = var.secrets_kms_key_arn

  storage_type          = "gp3"
  allocated_storage     = var.db_allocated_storage
  max_allocated_storage = var.db_max_allocated_storage
  storage_encrypted     = true
  kms_key_id            = var.data_kms_key_arn

  multi_az               = var.db_multi_az
  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [var.rds_security_group_id]
  publicly_accessible    = false
  parameter_group_name   = aws_db_parameter_group.this.name

  iam_database_authentication_enabled = true

  backup_retention_period   = var.db_backup_retention_days
  backup_window             = var.db_backup_window
  maintenance_window        = var.db_maintenance_window
  copy_tags_to_snapshot     = true
  delete_automated_backups  = false
  deletion_protection       = var.deletion_protection
  skip_final_snapshot       = false
  final_snapshot_identifier = "${local.db_identifier}-final"

  performance_insights_enabled          = true
  performance_insights_kms_key_id       = var.data_kms_key_arn
  performance_insights_retention_period = var.db_performance_insights_retention_days

  monitoring_interval = 60
  monitoring_role_arn = aws_iam_role.rds_monitoring.arn

  enabled_cloudwatch_logs_exports = local.db_log_exports

  apply_immediately = false

  tags = merge(var.tags, { Name = local.db_identifier, Role = "writer" })

  depends_on = [aws_cloudwatch_log_group.rds]
}

resource "aws_db_instance" "replica" {
  #checkov:skip=CKV_AWS_133:Read replica needs no own backups - the writer has 14-day PITR and the replica can be recreated from it at any time.
  #checkov:skip=CKV_AWS_157:The analytics read replica is single-AZ by default (db_replica_multi_az); losing it only degrades reporting and the writer is Multi-AZ.
  count = var.create_read_replica ? 1 : 0

  identifier          = local.replica_identifier
  replicate_source_db = aws_db_instance.writer.identifier

  instance_class             = var.db_replica_instance_class
  auto_minor_version_upgrade = true
  ca_cert_identifier         = var.db_ca_cert_identifier

  storage_type          = "gp3"
  max_allocated_storage = var.db_max_allocated_storage
  # Same-region replicas are always encrypted with the source's KMS key (kms_key_id must be omitted).
  storage_encrypted = true

  multi_az               = var.db_replica_multi_az
  vpc_security_group_ids = [var.rds_security_group_id]
  publicly_accessible    = false
  parameter_group_name   = aws_db_parameter_group.this.name

  iam_database_authentication_enabled = true

  # Replicas do not need their own backups (the writer has PITR).
  backup_retention_period = 0
  copy_tags_to_snapshot   = true
  deletion_protection     = var.deletion_protection
  skip_final_snapshot     = true
  maintenance_window      = var.db_maintenance_window

  performance_insights_enabled          = true
  performance_insights_kms_key_id       = var.data_kms_key_arn
  performance_insights_retention_period = var.db_performance_insights_retention_days

  monitoring_interval = 60
  monitoring_role_arn = aws_iam_role.rds_monitoring.arn

  enabled_cloudwatch_logs_exports = local.db_log_exports

  apply_immediately = false

  tags = merge(var.tags, { Name = local.replica_identifier, Role = "analytics-replica" })

  depends_on = [aws_cloudwatch_log_group.rds]
}

# ---------------------------------------------------------------------------- DR: cross-region backups

resource "aws_kms_key" "dr" {
  #checkov:skip=CKV2_AWS_64:Default key policy (root account delegation) is intended; access is granted via IAM and the RDS service grant.
  count    = var.enable_cross_region_backups ? 1 : 0
  provider = aws.dr

  description             = "${var.name_prefix} replicated RDS automated backups (DR region)"
  enable_key_rotation     = true
  deletion_window_in_days = 30
  tags                    = merge(var.tags, { Name = "${var.name_prefix}-dr-backups" })
}

resource "aws_db_instance_automated_backups_replication" "dr" {
  count    = var.enable_cross_region_backups ? 1 : 0
  provider = aws.dr

  source_db_instance_arn = aws_db_instance.writer.arn
  kms_key_id             = aws_kms_key.dr[0].arn
  retention_period       = var.dr_backup_retention_days
}

# ============================================================================ ElastiCache Redis

resource "aws_elasticache_subnet_group" "this" {
  name        = "${var.name_prefix}-redis"
  description = "Private data subnets for ${var.name_prefix} Redis"
  subnet_ids  = var.data_subnet_ids
  tags        = var.tags
}

resource "aws_elasticache_parameter_group" "this" {
  name        = "${var.name_prefix}-redis7"
  family      = "redis7"
  description = "${var.name_prefix} Redis 7 - every key has a TTL, so evict only TTL keys"

  parameter {
    name  = "maxmemory-policy"
    value = "volatile-lru"
  }

  tags = var.tags

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_cloudwatch_log_group" "redis" {
  # Retention is a variable (default 30 days, per AWS_DEPLOYMENT.md section 7); long-term retention is via S3 export.
  for_each = toset(["slow-log", "engine-log"])

  name              = "/pgms/${var.environment}/redis/${each.key}"
  retention_in_days = var.log_retention_days
  kms_key_id        = var.logs_kms_key_arn
  tags              = var.tags
}

resource "aws_elasticache_replication_group" "this" {
  replication_group_id = local.redis_id
  description          = "${var.name_prefix} Redis - cache, rate limiting, token revocation"

  engine               = "redis"
  engine_version       = var.redis_engine_version
  node_type            = var.redis_node_type
  port                 = 6379
  parameter_group_name = aws_elasticache_parameter_group.this.name

  num_cache_clusters         = var.redis_num_cache_clusters
  automatic_failover_enabled = true # requires >= 2 nodes (enforced by variable validation)
  multi_az_enabled           = true

  subnet_group_name  = aws_elasticache_subnet_group.this.name
  security_group_ids = [var.redis_security_group_id]

  at_rest_encryption_enabled = true
  kms_key_id                 = var.data_kms_key_arn
  transit_encryption_enabled = true
  transit_encryption_mode    = "required"
  auth_token                 = var.redis_auth_token
  auth_token_update_strategy = "ROTATE"

  snapshot_retention_limit   = var.redis_snapshot_retention_days
  snapshot_window            = var.redis_snapshot_window
  maintenance_window         = var.redis_maintenance_window
  auto_minor_version_upgrade = true
  apply_immediately          = false

  log_delivery_configuration {
    destination      = aws_cloudwatch_log_group.redis["slow-log"].name
    destination_type = "cloudwatch-logs"
    log_format       = "json"
    log_type         = "slow-log"
  }

  log_delivery_configuration {
    destination      = aws_cloudwatch_log_group.redis["engine-log"].name
    destination_type = "cloudwatch-logs"
    log_format       = "json"
    log_type         = "engine-log"
  }

  tags = merge(var.tags, { Name = local.redis_id })
}
