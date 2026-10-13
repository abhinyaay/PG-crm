###############################################################################
# Security groups exactly as specified in AWS_DEPLOYMENT.md section 3, using
# SG-to-SG references only (no CIDR rules except the public ALB listeners).
# The frontend SG is an addition: the doc predates the SPA container.
#
# | SG            | Inbound                                   | Outbound                                         |
# |---------------|-------------------------------------------|--------------------------------------------------|
# | alb           | 443 (+80 redirect) from 0.0.0.0/0         | 8080 backend, 8000 analytics, 80 frontend        |
# | backend       | 8080 from alb, 8080 from analytics (JWKS) | 5432 rds, 6379 redis, 443 endpoints (+S3 PL)     |
# | analytics     | 8000 from alb                             | 5432 rds, 6379 redis, 8080 backend, 443 endpoints|
# | frontend      | 80 from alb                               | 443 endpoints (+S3 PL)                           |
# | rds           | 5432 from backend, analytics              | none                                             |
# | redis         | 6379 from backend, analytics              | none                                             |
# | vpc-endpoints | 443 from backend, analytics, frontend     | none                                             |
###############################################################################

locals {
  # Task security groups that need AWS API access (ECR pull, Secrets Manager, CloudWatch Logs).
  task_sgs = {
    backend   = aws_security_group.backend.id
    analytics = aws_security_group.analytics.id
    frontend  = aws_security_group.frontend.id
  }
}

# ---------------------------------------------------------------------------- groups

resource "aws_security_group" "alb" {
  #checkov:skip=CKV2_AWS_5:Attached in another module (alb/ecs/data) via module outputs, which checkov's graph does not follow.
  name        = "${var.name_prefix}-alb"
  description = "Public ALB - HTTPS from the internet, HTTP only to redirect"
  vpc_id      = var.vpc_id
  tags        = merge(var.tags, { Name = "${var.name_prefix}-alb" })

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_security_group" "backend" {
  #checkov:skip=CKV2_AWS_5:Attached in another module (alb/ecs/data) via module outputs, which checkov's graph does not follow.
  name        = "${var.name_prefix}-backend"
  description = "pgms-backend ECS tasks"
  vpc_id      = var.vpc_id
  tags        = merge(var.tags, { Name = "${var.name_prefix}-backend" })

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_security_group" "analytics" {
  #checkov:skip=CKV2_AWS_5:Attached in another module (alb/ecs/data) via module outputs, which checkov's graph does not follow.
  name        = "${var.name_prefix}-analytics"
  description = "pgms-analytics ECS tasks"
  vpc_id      = var.vpc_id
  tags        = merge(var.tags, { Name = "${var.name_prefix}-analytics" })

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_security_group" "frontend" {
  #checkov:skip=CKV2_AWS_5:Attached in another module (alb/ecs/data) via module outputs, which checkov's graph does not follow.
  name        = "${var.name_prefix}-frontend"
  description = "pgms-frontend ECS tasks (static SPA)"
  vpc_id      = var.vpc_id
  tags        = merge(var.tags, { Name = "${var.name_prefix}-frontend" })

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_security_group" "rds" {
  #checkov:skip=CKV2_AWS_5:Attached in another module (alb/ecs/data) via module outputs, which checkov's graph does not follow.
  name        = "${var.name_prefix}-rds"
  description = "RDS PostgreSQL - only from the backend and analytics tasks"
  vpc_id      = var.vpc_id
  tags        = merge(var.tags, { Name = "${var.name_prefix}-rds" })

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_security_group" "redis" {
  #checkov:skip=CKV2_AWS_5:Attached in another module (alb/ecs/data) via module outputs, which checkov's graph does not follow.
  name        = "${var.name_prefix}-redis"
  description = "ElastiCache Redis - only from the backend and analytics tasks"
  vpc_id      = var.vpc_id
  tags        = merge(var.tags, { Name = "${var.name_prefix}-redis" })

  lifecycle {
    create_before_destroy = true
  }
}

# ---------------------------------------------------------------------------- ALB

resource "aws_vpc_security_group_ingress_rule" "alb_https" {
  security_group_id = aws_security_group.alb.id
  description       = "HTTPS from the internet"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}

resource "aws_vpc_security_group_ingress_rule" "alb_http" {
  #checkov:skip=CKV_AWS_260:Port 80 on the public ALB only serves a 301 redirect to HTTPS (AWS_DEPLOYMENT.md section 3).
  security_group_id = aws_security_group.alb.id
  description       = "HTTP from the internet - listener only issues a 301 redirect to HTTPS"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
}

resource "aws_vpc_security_group_egress_rule" "alb_to_backend" {
  security_group_id            = aws_security_group.alb.id
  description                  = "Forward to backend tasks"
  referenced_security_group_id = aws_security_group.backend.id
  ip_protocol                  = "tcp"
  from_port                    = var.backend_port
  to_port                      = var.backend_port
}

resource "aws_vpc_security_group_egress_rule" "alb_to_analytics" {
  security_group_id            = aws_security_group.alb.id
  description                  = "Forward to analytics tasks"
  referenced_security_group_id = aws_security_group.analytics.id
  ip_protocol                  = "tcp"
  from_port                    = var.analytics_port
  to_port                      = var.analytics_port
}

resource "aws_vpc_security_group_egress_rule" "alb_to_frontend" {
  security_group_id            = aws_security_group.alb.id
  description                  = "Forward to frontend tasks"
  referenced_security_group_id = aws_security_group.frontend.id
  ip_protocol                  = "tcp"
  from_port                    = var.frontend_port
  to_port                      = var.frontend_port
}

# ---------------------------------------------------------------------------- backend

resource "aws_vpc_security_group_ingress_rule" "backend_from_alb" {
  security_group_id            = aws_security_group.backend.id
  description                  = "ALB target traffic and health checks"
  referenced_security_group_id = aws_security_group.alb.id
  ip_protocol                  = "tcp"
  from_port                    = var.backend_port
  to_port                      = var.backend_port
}

resource "aws_vpc_security_group_ingress_rule" "backend_from_analytics" {
  security_group_id            = aws_security_group.backend.id
  description                  = "JWKS fetch from analytics via Service Connect"
  referenced_security_group_id = aws_security_group.analytics.id
  ip_protocol                  = "tcp"
  from_port                    = var.backend_port
  to_port                      = var.backend_port
}

resource "aws_vpc_security_group_egress_rule" "backend_to_rds" {
  security_group_id            = aws_security_group.backend.id
  description                  = "PostgreSQL writer"
  referenced_security_group_id = aws_security_group.rds.id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}

resource "aws_vpc_security_group_egress_rule" "backend_to_redis" {
  security_group_id            = aws_security_group.backend.id
  description                  = "Redis primary"
  referenced_security_group_id = aws_security_group.redis.id
  ip_protocol                  = "tcp"
  from_port                    = 6379
  to_port                      = 6379
}

# ---------------------------------------------------------------------------- analytics

resource "aws_vpc_security_group_ingress_rule" "analytics_from_alb" {
  security_group_id            = aws_security_group.analytics.id
  description                  = "ALB target traffic and health checks"
  referenced_security_group_id = aws_security_group.alb.id
  ip_protocol                  = "tcp"
  from_port                    = var.analytics_port
  to_port                      = var.analytics_port
}

resource "aws_vpc_security_group_egress_rule" "analytics_to_rds" {
  security_group_id            = aws_security_group.analytics.id
  description                  = "PostgreSQL read replica (analytics_ro)"
  referenced_security_group_id = aws_security_group.rds.id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}

resource "aws_vpc_security_group_egress_rule" "analytics_to_redis" {
  security_group_id            = aws_security_group.analytics.id
  description                  = "Redis primary"
  referenced_security_group_id = aws_security_group.redis.id
  ip_protocol                  = "tcp"
  from_port                    = 6379
  to_port                      = 6379
}

resource "aws_vpc_security_group_egress_rule" "analytics_to_backend" {
  security_group_id            = aws_security_group.analytics.id
  description                  = "JWKS fetch from backend via Service Connect"
  referenced_security_group_id = aws_security_group.backend.id
  ip_protocol                  = "tcp"
  from_port                    = var.backend_port
  to_port                      = var.backend_port
}

# ---------------------------------------------------------------------------- frontend

resource "aws_vpc_security_group_ingress_rule" "frontend_from_alb" {
  #checkov:skip=CKV_AWS_260:False positive - the source is the ALB security group, not 0.0.0.0/0.
  security_group_id            = aws_security_group.frontend.id
  description                  = "ALB target traffic and health checks"
  referenced_security_group_id = aws_security_group.alb.id
  ip_protocol                  = "tcp"
  from_port                    = var.frontend_port
  to_port                      = var.frontend_port
}

# ---------------------------------------------------------------------------- shared task egress

resource "aws_vpc_security_group_egress_rule" "tasks_to_endpoints" {
  for_each = local.task_sgs

  security_group_id            = each.value
  description                  = "HTTPS to interface VPC endpoints (ECR, Secrets Manager, CloudWatch Logs)"
  referenced_security_group_id = var.endpoints_security_group_id
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443
}

resource "aws_vpc_security_group_egress_rule" "tasks_to_s3" {
  for_each = local.task_sgs

  security_group_id = each.value
  description       = "HTTPS to S3 via the gateway endpoint (ECR image layers)"
  prefix_list_id    = var.s3_prefix_list_id
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}

# Opt-in only: needed when interface endpoints are disabled (AWS APIs reached through NAT) or the
# application starts calling external HTTPS APIs (payment gateway, e-mail provider, ...).
resource "aws_vpc_security_group_egress_rule" "tasks_to_internet" {
  # Opt-in (allow_https_egress_to_internet, default false) and limited to tcp/443 for NAT-routed AWS APIs or third-party HTTPS APIs.
  for_each = var.allow_https_egress_to_internet ? local.task_sgs : {}

  security_group_id = each.value
  description       = "HTTPS to the internet through NAT"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}

# ---------------------------------------------------------------------------- data tier

resource "aws_vpc_security_group_ingress_rule" "rds_from_tasks" {
  for_each = {
    backend   = aws_security_group.backend.id
    analytics = aws_security_group.analytics.id
  }

  security_group_id            = aws_security_group.rds.id
  description                  = "PostgreSQL from ${each.key} tasks"
  referenced_security_group_id = each.value
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}

resource "aws_vpc_security_group_ingress_rule" "redis_from_tasks" {
  for_each = {
    backend   = aws_security_group.backend.id
    analytics = aws_security_group.analytics.id
  }

  security_group_id            = aws_security_group.redis.id
  description                  = "Redis from ${each.key} tasks"
  referenced_security_group_id = each.value
  ip_protocol                  = "tcp"
  from_port                    = 6379
  to_port                      = 6379
}

# ---------------------------------------------------------------------------- VPC endpoints

resource "aws_vpc_security_group_ingress_rule" "endpoints_from_tasks" {
  for_each = local.task_sgs

  security_group_id            = var.endpoints_security_group_id
  description                  = "HTTPS from ${each.key} tasks"
  referenced_security_group_id = each.value
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443
}
