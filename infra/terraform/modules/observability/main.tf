###############################################################################
# Logging, metrics and alerting (AWS_DEPLOYMENT.md section 7)
#   - KMS-encrypted log groups /pgms/<env>/<service>
#   - SNS alarm topic (KMS-encrypted) + optional e-mail subscriptions
#   - Alarms: ALB 5xx rate, p95 latency, backend healthy hosts, RDS CPU /
#     connections / replica lag / free storage, Redis evictions / engine CPU,
#     ERROR log rate per service
###############################################################################

locals {
  alarm_prefix = var.name_prefix
  actions      = [aws_sns_topic.alarms.arn]

  error_log_services = { for s in var.error_log_alarm_services : s => aws_cloudwatch_log_group.service[s].name }

  rds_instances = merge(
    { writer = var.db_writer_identifier },
    var.db_replica_identifier == null ? {} : { replica = var.db_replica_identifier },
  )
}

# ---------------------------------------------------------------------------- logs

resource "aws_cloudwatch_log_group" "service" {
  # AWS_DEPLOYMENT.md section 7 specifies 30-day retention in CloudWatch with S3 export for long-term retention.
  for_each = toset(var.services)

  name              = "/pgms/${var.environment}/${each.key}"
  retention_in_days = var.log_retention_days
  kms_key_id        = var.logs_kms_key_arn

  tags = merge(var.tags, { Service = each.key })
}

# ---------------------------------------------------------------------------- SNS

resource "aws_sns_topic" "alarms" {
  name              = "${var.name_prefix}-alarms"
  kms_master_key_id = var.logs_kms_key_arn
  tags              = var.tags
}

data "aws_iam_policy_document" "alarms_topic" {
  statement {
    sid       = "AllowCloudWatchAlarmsFromThisAccount"
    actions   = ["sns:Publish"]
    resources = [aws_sns_topic.alarms.arn]
    principals {
      type        = "Service"
      identifiers = ["cloudwatch.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [var.account_id]
    }
  }

  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["sns:Publish"]
    resources = [aws_sns_topic.alarms.arn]
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_sns_topic_policy" "alarms" {
  arn    = aws_sns_topic.alarms.arn
  policy = data.aws_iam_policy_document.alarms_topic.json
}

resource "aws_sns_topic_subscription" "email" {
  for_each = toset(var.alarm_email_addresses)

  topic_arn = aws_sns_topic.alarms.arn
  protocol  = "email"
  endpoint  = each.key
}

# ---------------------------------------------------------------------------- ALB

resource "aws_cloudwatch_metric_alarm" "alb_5xx_rate" {
  alarm_name          = "${local.alarm_prefix}-alb-target-5xx-rate"
  alarm_description   = "Target 5xx responses > ${var.alb_5xx_rate_threshold_percent}% of requests for 5 minutes"
  comparison_operator = "GreaterThanThreshold"
  threshold           = var.alb_5xx_rate_threshold_percent
  evaluation_periods  = 5
  datapoints_to_alarm = 5
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.actions
  ok_actions          = local.actions

  metric_query {
    id          = "rate"
    expression  = "IF(requests >= ${var.alb_5xx_min_requests_per_minute}, 100 * errors / requests, 0)"
    label       = "Target 5xx %"
    return_data = true
  }

  metric_query {
    id = "errors"
    metric {
      namespace   = "AWS/ApplicationELB"
      metric_name = "HTTPCode_Target_5XX_Count"
      period      = 60
      stat        = "Sum"
      dimensions  = { LoadBalancer = var.alb_arn_suffix }
    }
  }

  metric_query {
    id = "requests"
    metric {
      namespace   = "AWS/ApplicationELB"
      metric_name = "RequestCount"
      period      = 60
      stat        = "Sum"
      dimensions  = { LoadBalancer = var.alb_arn_suffix }
    }
  }

  tags = var.tags
}

resource "aws_cloudwatch_metric_alarm" "alb_p95_latency" {
  alarm_name          = "${local.alarm_prefix}-alb-target-response-time-p95"
  alarm_description   = "ALB TargetResponseTime p95 > ${var.alb_p95_latency_threshold_seconds}s for 5 minutes"
  namespace           = "AWS/ApplicationELB"
  metric_name         = "TargetResponseTime"
  dimensions          = { LoadBalancer = var.alb_arn_suffix }
  extended_statistic  = "p95"
  period              = 60
  evaluation_periods  = 5
  datapoints_to_alarm = 5
  comparison_operator = "GreaterThanThreshold"
  threshold           = var.alb_p95_latency_threshold_seconds
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.actions
  ok_actions          = local.actions
  tags                = var.tags
}

resource "aws_cloudwatch_metric_alarm" "backend_healthy_hosts" {
  alarm_name          = "${local.alarm_prefix}-backend-healthy-hosts"
  alarm_description   = "Fewer than ${var.backend_min_healthy_hosts} healthy backend targets"
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HealthyHostCount"
  dimensions          = { LoadBalancer = var.alb_arn_suffix, TargetGroup = var.backend_target_group_arn_suffix }
  statistic           = "Minimum"
  period              = 60
  evaluation_periods  = 3
  datapoints_to_alarm = 3
  comparison_operator = "LessThanThreshold"
  threshold           = var.backend_min_healthy_hosts
  treat_missing_data  = "breaching"
  alarm_actions       = local.actions
  ok_actions          = local.actions
  tags                = var.tags
}

# ---------------------------------------------------------------------------- RDS

resource "aws_cloudwatch_metric_alarm" "rds_cpu" {
  for_each = local.rds_instances

  alarm_name          = "${local.alarm_prefix}-rds-${each.key}-cpu"
  alarm_description   = "RDS ${each.value} CPU > 80% for 10 minutes"
  namespace           = "AWS/RDS"
  metric_name         = "CPUUtilization"
  dimensions          = { DBInstanceIdentifier = each.value }
  statistic           = "Average"
  period              = 60
  evaluation_periods  = 10
  datapoints_to_alarm = 10
  comparison_operator = "GreaterThanThreshold"
  threshold           = 80
  treat_missing_data  = "missing"
  alarm_actions       = local.actions
  ok_actions          = local.actions
  tags                = var.tags
}

resource "aws_cloudwatch_metric_alarm" "rds_connections" {
  for_each = local.rds_instances

  alarm_name          = "${local.alarm_prefix}-rds-${each.key}-connections"
  alarm_description   = "RDS ${each.value} DatabaseConnections > 80% of max_connections (${var.db_max_connections})"
  namespace           = "AWS/RDS"
  metric_name         = "DatabaseConnections"
  dimensions          = { DBInstanceIdentifier = each.value }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 5
  datapoints_to_alarm = 5
  comparison_operator = "GreaterThanThreshold"
  threshold           = floor(var.db_max_connections * 0.8)
  treat_missing_data  = "missing"
  alarm_actions       = local.actions
  ok_actions          = local.actions
  tags                = var.tags
}

resource "aws_cloudwatch_metric_alarm" "rds_replica_lag" {
  count = var.db_replica_identifier == null ? 0 : 1

  alarm_name          = "${local.alarm_prefix}-rds-replica-lag"
  alarm_description   = "Analytics read replica lag > 60 s"
  namespace           = "AWS/RDS"
  metric_name         = "ReplicaLag"
  dimensions          = { DBInstanceIdentifier = var.db_replica_identifier }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 5
  datapoints_to_alarm = 5
  comparison_operator = "GreaterThanThreshold"
  threshold           = 60
  treat_missing_data  = "missing"
  alarm_actions       = local.actions
  ok_actions          = local.actions
  tags                = var.tags
}

resource "aws_cloudwatch_metric_alarm" "rds_free_storage" {
  for_each = local.rds_instances

  alarm_name          = "${local.alarm_prefix}-rds-${each.key}-free-storage"
  alarm_description   = "RDS ${each.value} FreeStorageSpace < 15% of the initially allocated ${var.db_allocated_storage_gib} GiB (storage autoscaling may already be reacting)"
  namespace           = "AWS/RDS"
  metric_name         = "FreeStorageSpace"
  dimensions          = { DBInstanceIdentifier = each.value }
  statistic           = "Minimum"
  period              = 300
  evaluation_periods  = 2
  datapoints_to_alarm = 2
  comparison_operator = "LessThanThreshold"
  threshold           = floor(var.db_allocated_storage_gib * 1073741824 * 0.15)
  treat_missing_data  = "missing"
  alarm_actions       = local.actions
  ok_actions          = local.actions
  tags                = var.tags
}

# ---------------------------------------------------------------------------- ElastiCache

resource "aws_cloudwatch_metric_alarm" "redis_evictions" {
  count = length(var.redis_member_cluster_ids)

  alarm_name          = "${local.alarm_prefix}-redis-${var.redis_member_cluster_ids[count.index]}-evictions"
  alarm_description   = "Redis evictions sustained for 15 minutes (memory pressure; only TTL keys are evicted)"
  namespace           = "AWS/ElastiCache"
  metric_name         = "Evictions"
  dimensions          = { CacheClusterId = var.redis_member_cluster_ids[count.index] }
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 3
  datapoints_to_alarm = 3
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.actions
  ok_actions          = local.actions
  tags                = var.tags
}

resource "aws_cloudwatch_metric_alarm" "redis_engine_cpu" {
  count = length(var.redis_member_cluster_ids)

  alarm_name          = "${local.alarm_prefix}-redis-${var.redis_member_cluster_ids[count.index]}-engine-cpu"
  alarm_description   = "Redis EngineCPUUtilization > 80% for 10 minutes"
  namespace           = "AWS/ElastiCache"
  metric_name         = "EngineCPUUtilization"
  dimensions          = { CacheClusterId = var.redis_member_cluster_ids[count.index] }
  statistic           = "Average"
  period              = 60
  evaluation_periods  = 10
  datapoints_to_alarm = 10
  comparison_operator = "GreaterThanThreshold"
  threshold           = 80
  treat_missing_data  = "missing"
  alarm_actions       = local.actions
  ok_actions          = local.actions
  tags                = var.tags
}

# ---------------------------------------------------------------------------- ERROR log rate

resource "aws_cloudwatch_log_metric_filter" "errors" {
  for_each = local.error_log_services

  name           = "${var.name_prefix}-${each.key}-error-logs"
  log_group_name = each.value
  pattern        = "{ $.level = \"ERROR\" }"

  metric_transformation {
    name          = "${each.key}-error-log-count"
    namespace     = "PGMS/${var.environment}"
    value         = "1"
    default_value = "0"
    unit          = "Count"
  }
}

resource "aws_cloudwatch_metric_alarm" "error_logs" {
  for_each = local.error_log_services

  alarm_name          = "${local.alarm_prefix}-${each.key}-error-log-rate"
  alarm_description   = "More than ${var.error_log_threshold_per_5min} ERROR log lines in 5 minutes from ${each.key}"
  namespace           = "PGMS/${var.environment}"
  metric_name         = aws_cloudwatch_log_metric_filter.errors[each.key].metric_transformation[0].name
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  comparison_operator = "GreaterThanThreshold"
  threshold           = var.error_log_threshold_per_5min
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.actions
  ok_actions          = local.actions
  tags                = var.tags
}
