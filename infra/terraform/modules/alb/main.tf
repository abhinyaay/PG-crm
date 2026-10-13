###############################################################################
# Edge: ACM certificate, public ALB (TLS termination + path routing that nginx
# does locally), access logs to S3, AWS WAF.
#
# Listener 443 rules (first match wins):
#   10  /actuator, /actuator/*                 -> fixed 404 (never public)
#   11  /swagger-ui*, /v3/api-docs*            -> fixed 404 (API docs off in prod)
#   20  /api/*                                 -> backend   (8080, /actuator/health/readiness)
#   30  /analytics/*                           -> analytics (8000, /health/ready)
#   default                                    -> frontend  (80,   /healthz)
###############################################################################

data "aws_caller_identity" "current" {}
data "aws_elb_service_account" "current" {}

locals {
  account_id = data.aws_caller_identity.current.account_id

  create_certificate = var.certificate_arn == null
  validate_with_dns  = local.create_certificate && var.route53_zone_id != null

  certificate_domains = distinct(concat([var.domain_name], var.subject_alternative_names))

  certificate_arn = (
    !local.create_certificate ? var.certificate_arn :
    local.validate_with_dns ? aws_acm_certificate_validation.this[0].certificate_arn :
    aws_acm_certificate.this[0].arn
  )

  log_prefix = "alb"

  target_groups = {
    backend = {
      port              = var.backend_port
      health_check_path = "/actuator/health/readiness"
      interval          = 15
    }
    analytics = {
      port              = var.analytics_port
      health_check_path = "/health/ready"
      interval          = 15
    }
    frontend = {
      port              = var.frontend_port
      health_check_path = "/healthz"
      interval          = 15
    }
  }

  not_found_body = jsonencode({ status = 404, code = "NOT_FOUND", message = "Not found" })
}

# ============================================================================ access log bucket

resource "aws_s3_bucket" "access_logs" {
  #checkov:skip=CKV_AWS_18:This is the log sink itself; logging it to another bucket would recurse.
  #checkov:skip=CKV_AWS_144:Cross-region replication of ALB access logs is not required (RPO for logs is best-effort).
  #checkov:skip=CKV2_AWS_62:No consumer needs event notifications for access-log objects.
  #checkov:skip=CKV_AWS_145:ALB access logs only support SSE-S3 (AES256), not SSE-KMS.
  bucket        = "${var.name_prefix}-alb-access-logs-${local.account_id}"
  force_destroy = false
  tags          = var.tags
}

resource "aws_s3_bucket_ownership_controls" "access_logs" {
  bucket = aws_s3_bucket.access_logs.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_public_access_block" "access_logs" {
  bucket                  = aws_s3_bucket.access_logs.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_versioning" "access_logs" {
  bucket = aws_s3_bucket.access_logs.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "access_logs" {
  bucket = aws_s3_bucket.access_logs.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256" # the only option supported by ELB log delivery
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "access_logs" {
  bucket = aws_s3_bucket.access_logs.id

  rule {
    id     = "expire-access-logs"
    status = "Enabled"
    filter {}

    transition {
      days          = 30
      storage_class = "STANDARD_IA"
    }

    expiration {
      days = var.access_log_retention_days
    }

    noncurrent_version_expiration {
      noncurrent_days = 7
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }
}

data "aws_iam_policy_document" "access_logs" {
  # Regions launched before Aug 2022 (incl. ap-south-1) deliver as the regional ELB account ...
  statement {
    sid       = "AllowElbAccountLogDelivery"
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.access_logs.arn}/${local.log_prefix}/AWSLogs/${local.account_id}/*"]
    principals {
      type        = "AWS"
      identifiers = [data.aws_elb_service_account.current.arn]
    }
  }

  # ... newer regions deliver as the log-delivery service principal.
  statement {
    sid       = "AllowElbServiceLogDelivery"
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.access_logs.arn}/${local.log_prefix}/AWSLogs/${local.account_id}/*"]
    principals {
      type        = "Service"
      identifiers = ["logdelivery.elasticloadbalancing.amazonaws.com"]
    }
  }

  statement {
    sid     = "DenyInsecureTransport"
    effect  = "Deny"
    actions = ["s3:*"]
    resources = [
      aws_s3_bucket.access_logs.arn,
      "${aws_s3_bucket.access_logs.arn}/*",
    ]
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

resource "aws_s3_bucket_policy" "access_logs" {
  bucket = aws_s3_bucket.access_logs.id
  policy = data.aws_iam_policy_document.access_logs.json

  depends_on = [aws_s3_bucket_public_access_block.access_logs]
}

# ============================================================================ certificate

resource "aws_acm_certificate" "this" {
  count = local.create_certificate ? 1 : 0

  domain_name               = var.domain_name
  subject_alternative_names = var.subject_alternative_names
  validation_method         = "DNS"
  key_algorithm             = "RSA_2048"

  options {
    certificate_transparency_logging_preference = "ENABLED"
  }

  tags = merge(var.tags, { Name = var.domain_name })

  lifecycle {
    create_before_destroy = true
  }
}

# Keyed by the configured hostnames (known at plan time) rather than by domain_validation_options,
# whose contents are only known after the certificate is requested.
resource "aws_route53_record" "certificate_validation" {
  for_each = local.validate_with_dns ? toset(local.certificate_domains) : toset([])

  zone_id = var.route53_zone_id
  name    = one([for o in aws_acm_certificate.this[0].domain_validation_options : o.resource_record_name if o.domain_name == each.key])
  type    = one([for o in aws_acm_certificate.this[0].domain_validation_options : o.resource_record_type if o.domain_name == each.key])
  records = [one([for o in aws_acm_certificate.this[0].domain_validation_options : o.resource_record_value if o.domain_name == each.key])]
  ttl     = 300
  # an apex and its wildcard share one validation record
  allow_overwrite = true
}

resource "aws_acm_certificate_validation" "this" {
  count = local.validate_with_dns ? 1 : 0

  certificate_arn         = aws_acm_certificate.this[0].arn
  validation_record_fqdns = [for r in aws_route53_record.certificate_validation : r.fqdn]
}

# ============================================================================ load balancer

resource "aws_lb" "this" {
  #checkov:skip=CKV2_AWS_76:Log4j signatures are covered by AWSManagedRulesKnownBadInputsRuleSet (attached). The check also demands AWSManagedRulesAnonymousIpList, which would block legitimate residents/staff on VPNs or iCloud Private Relay.
  name               = var.name_prefix
  load_balancer_type = "application"
  internal           = false
  ip_address_type    = "ipv4"
  security_groups    = [var.alb_security_group_id]
  subnets            = var.public_subnet_ids

  drop_invalid_header_fields = true
  desync_mitigation_mode     = "defensive"
  enable_deletion_protection = var.deletion_protection
  enable_http2               = true
  idle_timeout               = var.idle_timeout_seconds

  access_logs {
    bucket  = aws_s3_bucket.access_logs.id
    prefix  = local.log_prefix
    enabled = true
  }

  tags = merge(var.tags, { Name = var.name_prefix })

  depends_on = [aws_s3_bucket_policy.access_logs]
}

resource "aws_lb_target_group" "this" {
  #checkov:skip=CKV_AWS_378:TLS terminates at the ALB; targets are in private subnets reachable only from the ALB security group. End-to-end TLS would need certificates in every container.
  for_each = local.target_groups

  name                 = "${var.name_prefix}-${each.key}"
  vpc_id               = var.vpc_id
  target_type          = "ip"
  protocol             = "HTTP"
  protocol_version     = "HTTP1"
  port                 = each.value.port
  deregistration_delay = var.deregistration_delay_seconds

  health_check {
    enabled             = true
    path                = each.value.health_check_path
    protocol            = "HTTP"
    port                = "traffic-port"
    matcher             = "200"
    interval            = each.value.interval
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }

  tags = merge(var.tags, { Service = each.key })
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.this.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "redirect"
    redirect {
      protocol    = "HTTPS"
      port        = "443"
      status_code = "HTTP_301"
    }
  }

  tags = var.tags
}

resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.this.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = var.ssl_policy
  certificate_arn   = local.certificate_arn

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.this["frontend"].arn
  }

  tags = var.tags
}

resource "aws_lb_listener_rule" "block_actuator" {
  listener_arn = aws_lb_listener.https.arn
  priority     = 10

  action {
    type = "fixed-response"
    fixed_response {
      content_type = "application/json"
      message_body = local.not_found_body
      status_code  = "404"
    }
  }

  condition {
    path_pattern {
      values = ["/actuator", "/actuator/*"]
    }
  }

  tags = var.tags
}

resource "aws_lb_listener_rule" "block_api_docs" {
  count = var.expose_api_docs ? 0 : 1

  listener_arn = aws_lb_listener.https.arn
  priority     = 11

  action {
    type = "fixed-response"
    fixed_response {
      content_type = "application/json"
      message_body = local.not_found_body
      status_code  = "404"
    }
  }

  condition {
    path_pattern {
      values = ["/swagger-ui*", "/v3/api-docs*"]
    }
  }

  tags = var.tags
}

resource "aws_lb_listener_rule" "backend" {
  listener_arn = aws_lb_listener.https.arn
  priority     = 20

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.this["backend"].arn
  }

  condition {
    path_pattern {
      values = var.expose_api_docs ? ["/api/*", "/swagger-ui*", "/v3/api-docs*"] : ["/api/*"]
    }
  }

  tags = var.tags
}

resource "aws_lb_listener_rule" "analytics" {
  listener_arn = aws_lb_listener.https.arn
  priority     = 30

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.this["analytics"].arn
  }

  condition {
    path_pattern {
      values = ["/analytics/*"]
    }
  }

  tags = var.tags
}

resource "aws_route53_record" "alias" {
  for_each = var.route53_zone_id == null ? toset([]) : toset(local.certificate_domains)

  zone_id = var.route53_zone_id
  name    = each.key
  type    = "A"

  alias {
    name                   = aws_lb.this.dns_name
    zone_id                = aws_lb.this.zone_id
    evaluate_target_health = true
  }
}

# ============================================================================ WAF

resource "aws_wafv2_web_acl" "this" {
  name        = var.name_prefix
  description = "${var.name_prefix} ALB - AWS managed rules + per-IP rate limits"
  scope       = "REGIONAL"

  default_action {
    allow {}
  }

  custom_response_body {
    key          = "rate-limited"
    content_type = "APPLICATION_JSON"
    content      = jsonencode({ status = 429, code = "TOO_MANY_REQUESTS", message = "Too many requests" })
  }

  rule {
    name     = "aws-ip-reputation"
    priority = 0

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        vendor_name = "AWS"
        name        = "AWSManagedRulesAmazonIpReputationList"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.name_prefix}-ip-reputation"
      sampled_requests_enabled   = true
    }
  }

  # Credential endpoints: mirrors nginx's auth zone (20 r/min) at WAF granularity (per 5 min window).
  rule {
    name     = "rate-limit-auth"
    priority = 1

    action {
      block {
        custom_response {
          response_code            = 429
          custom_response_body_key = "rate-limited"
        }
      }
    }

    statement {
      rate_based_statement {
        limit                 = var.waf_auth_rate_limit_per_5min
        aggregate_key_type    = "IP"
        evaluation_window_sec = 300

        scope_down_statement {
          byte_match_statement {
            search_string         = "/api/v1/auth/"
            positional_constraint = "STARTS_WITH"

            field_to_match {
              uri_path {}
            }

            text_transformation {
              priority = 0
              type     = "LOWERCASE"
            }
          }
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.name_prefix}-rate-limit-auth"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "rate-limit-per-ip"
    priority = 2

    action {
      block {
        custom_response {
          response_code            = 429
          custom_response_body_key = "rate-limited"
        }
      }
    }

    statement {
      rate_based_statement {
        limit                 = var.waf_rate_limit_per_5min
        aggregate_key_type    = "IP"
        evaluation_window_sec = 300
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.name_prefix}-rate-limit-per-ip"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "aws-common"
    priority = 10

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        vendor_name = "AWS"
        name        = "AWSManagedRulesCommonRuleSet"

        # e.g. SizeRestrictions_BODY (8 KB) would block legitimate larger JSON bodies (nginx allows 2 MB).
        dynamic "rule_action_override" {
          for_each = toset(var.waf_common_rules_to_count)
          content {
            name = rule_action_override.value
            action_to_use {
              count {}
            }
          }
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.name_prefix}-common"
      sampled_requests_enabled   = true
    }
  }

  # Includes the Log4j/Log4Shell signatures (relevant for the Java backend).
  rule {
    name     = "aws-known-bad-inputs"
    priority = 20

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        vendor_name = "AWS"
        name        = "AWSManagedRulesKnownBadInputsRuleSet"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.name_prefix}-known-bad-inputs"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "aws-sqli"
    priority = 30

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        vendor_name = "AWS"
        name        = "AWSManagedRulesSQLiRuleSet"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.name_prefix}-sqli"
      sampled_requests_enabled   = true
    }
  }

  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = var.name_prefix
    sampled_requests_enabled   = true
  }

  tags = var.tags
}

resource "aws_wafv2_web_acl_association" "this" {
  resource_arn = aws_lb.this.arn
  web_acl_arn  = aws_wafv2_web_acl.this.arn
}

resource "aws_cloudwatch_log_group" "waf" {
  # Retention is a variable (default 30 days, per AWS_DEPLOYMENT.md section 7).
  name              = "aws-waf-logs-${var.name_prefix}" # prefix mandated by WAF
  retention_in_days = var.waf_log_retention_days
  kms_key_id        = var.logs_kms_key_arn
  tags              = var.tags
}

resource "aws_wafv2_web_acl_logging_configuration" "this" {
  resource_arn            = aws_wafv2_web_acl.this.arn
  log_destination_configs = [aws_cloudwatch_log_group.waf.arn]

  redacted_fields {
    single_header {
      name = "authorization"
    }
  }

  redacted_fields {
    single_header {
      name = "cookie"
    }
  }
}
