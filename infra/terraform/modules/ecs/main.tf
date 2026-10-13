###############################################################################
# ECS on Fargate (AWS_DEPLOYMENT.md section 4)
#   - cluster with Container Insights and a Service Connect namespace
#   - per service: task definition, execution role (pull image, write logs,
#     read only its own secrets), task role (no AWS permissions by default),
#     service with rolling deployment + circuit breaker rollback, ALB target
#     registration, target-tracking autoscaling on CPU and request count
#
# Ownership split with CI/CD: Terraform owns the task definition *shape*
# (env, secrets, sizing, health checks); the pipeline owns the *image*.
#   - The pipeline reads the latest revision of each family, swaps the image,
#     registers a new revision, deploys it, then records the deployed tag in
#     the SSM parameter <image_tag_parameter_prefix>/<service>.
#   - Terraform builds the image reference from that parameter (value changes
#     are ignored, but refreshed), so every revision Terraform registers runs
#     the image that is currently deployed - never a stale bootstrap tag.
#   - The service ignores task_definition changes; Terraform-side changes roll
#     out with the next pipeline run or an explicit `aws ecs update-service
#     --task-definition <family>` (safe, see README).
###############################################################################

data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}
data "aws_region" "current" {}

locals {
  account_id = data.aws_caller_identity.current.account_id
  partition  = data.aws_partition.current.partition
  region     = data.aws_region.current.region

  container_definitions = {
    for name, svc in var.services : name => [
      merge(
        {
          name      = name
          image     = "${svc.repository_url}:${aws_ssm_parameter.image_tag[name].insecure_value}"
          essential = true

          portMappings = [{
            name          = "http"
            containerPort = svc.port
            hostPort      = svc.port
            protocol      = "tcp"
            appProtocol   = "http"
          }]

          environment = [for k in sort(keys(svc.environment)) : { name = k, value = svc.environment[k] }]
          secrets     = [for k in sort(keys(svc.secrets)) : { name = k, valueFrom = svc.secrets[k] }]

          healthCheck = {
            command     = svc.health_check.command
            interval    = svc.health_check.interval
            timeout     = svc.health_check.timeout
            retries     = svc.health_check.retries
            startPeriod = svc.health_check.start_period
          }

          stopTimeout            = svc.stop_timeout
          readonlyRootFilesystem = svc.readonly_root_filesystem

          # tini-style init: reaps zombies, forwards SIGTERM (graceful shutdown); required by ECS Exec
          linuxParameters = { initProcessEnabled = true }

          mountPoints = []
          volumesFrom = []

          logConfiguration = {
            logDriver = "awslogs"
            options = {
              awslogs-group         = svc.log_group_name
              awslogs-region        = local.region
              awslogs-stream-prefix = name
              mode                  = "non-blocking"
              max-buffer-size       = "25m"
            }
          }
        },
        length(svc.system_controls) > 0 ? {
          systemControls = [for k, v in svc.system_controls : { namespace = k, value = v }]
        } : {},
      )
    ]
  }

  autoscaled_request_policies = {
    # filter on a plan-time-known value (target group ARNs are only known after apply)
    for name, svc in var.services : name => svc
    if svc.requests_per_target != null
  }
}

# ============================================================================ image tags

resource "aws_ssm_parameter" "image_tag" {
  #checkov:skip=CKV2_AWS_34:Holds a public image tag (commit SHA), not a secret; String keeps it readable in plans.
  for_each = var.services

  name           = "${var.image_tag_parameter_prefix}/${each.key}"
  description    = "Image tag of ${each.key} currently deployed by CI/CD (written by the pipeline)"
  type           = "String"
  insecure_value = each.value.image_tag
  tags           = var.tags

  lifecycle {
    # Written by the pipeline after every deployment; Terraform only seeds it.
    ignore_changes = [insecure_value]
  }
}

# ============================================================================ cluster

resource "aws_service_discovery_http_namespace" "this" {
  name        = var.service_connect_namespace
  description = "Service Connect namespace for ${var.cluster_name}"
  tags        = var.tags
}

resource "aws_ecs_cluster" "this" {
  #checkov:skip=CKV_AWS_65:Container Insights is always on - var.container_insights is validated to "enabled" or "enhanced" (checkov cannot resolve the variable).
  name = var.cluster_name

  setting {
    name  = "containerInsights"
    value = var.container_insights
  }

  service_connect_defaults {
    namespace = aws_service_discovery_http_namespace.this.arn
  }

  tags = var.tags
}

resource "aws_ecs_cluster_capacity_providers" "this" {
  cluster_name       = aws_ecs_cluster.this.name
  capacity_providers = ["FARGATE", "FARGATE_SPOT"]

  default_capacity_provider_strategy {
    capacity_provider = "FARGATE"
    weight            = 1
    base              = 0
  }
}

# ============================================================================ IAM

data "aws_iam_policy_document" "ecs_tasks_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
    # confused-deputy protection: only ECS acting for this account
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

# ---------------------------------------------------------------------------- execution roles

resource "aws_iam_role" "execution" {
  for_each = var.services

  name               = "${var.name_prefix}-${each.key}-execution"
  description        = "ECS agent for ${each.key}: pull image, write logs, read its own secrets"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
  tags               = var.tags
}

data "aws_iam_policy_document" "execution" {
  for_each = var.services

  # ecr:GetAuthorizationToken does not support resource-level permissions.
  statement {
    sid       = "EcrAuth"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid = "EcrPullOwnRepository"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:BatchGetImage",
      "ecr:GetDownloadUrlForLayer",
    ]
    resources = [each.value.repository_arn]
  }

  statement {
    sid       = "WriteOwnLogs"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["arn:${local.partition}:logs:${local.region}:${local.account_id}:log-group:${each.value.log_group_name}:*"]
  }

  dynamic "statement" {
    for_each = length(each.value.secret_arns) > 0 ? [1] : []
    content {
      sid       = "ReadOwnSecrets"
      actions   = ["secretsmanager:GetSecretValue"]
      resources = each.value.secret_arns
    }
  }

  dynamic "statement" {
    for_each = length(each.value.secret_arns) > 0 ? [1] : []
    content {
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
}

resource "aws_iam_role_policy" "execution" {
  for_each = var.services

  name   = "execution"
  role   = aws_iam_role.execution[each.key].id
  policy = data.aws_iam_policy_document.execution[each.key].json
}

# ---------------------------------------------------------------------------- task roles

# The applications call no AWS APIs today (AWS_DEPLOYMENT.md section 8), so task roles carry no
# permissions unless ECS Exec (break-glass shell) is enabled.
resource "aws_iam_role" "task" {
  for_each = var.services

  name               = "${var.name_prefix}-${each.key}-task"
  description        = "Application role of ${each.key} (no AWS permissions by default)"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
  tags               = var.tags
}

data "aws_iam_policy_document" "ecs_exec" {
  # ssmmessages channel actions do not support resource-level permissions.
  statement {
    sid = "EcsExecSessionManagerChannels"
    actions = [
      "ssmmessages:CreateControlChannel",
      "ssmmessages:CreateDataChannel",
      "ssmmessages:OpenControlChannel",
      "ssmmessages:OpenDataChannel",
    ]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "ecs_exec" {
  for_each = var.enable_execute_command ? var.services : {}

  name   = "ecs-exec"
  role   = aws_iam_role.task[each.key].id
  policy = data.aws_iam_policy_document.ecs_exec.json
}

# ============================================================================ task definitions

resource "aws_ecs_task_definition" "this" {
  # readonlyRootFilesystem is a per-service variable; it needs VOLUME /tmp (and /var/cache/nginx) in the Dockerfiles first, see README.
  for_each = var.services

  family                   = "${var.name_prefix}-${each.key}"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = tostring(each.value.cpu)
  memory                   = tostring(each.value.memory)
  execution_role_arn       = aws_iam_role.execution[each.key].arn
  task_role_arn            = aws_iam_role.task[each.key].arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = each.value.cpu_architecture
  }

  container_definitions = jsonencode(local.container_definitions[each.key])

  # Keep old revisions registered: CI and the circuit breaker roll back to them.
  skip_destroy = true

  tags = merge(var.tags, { Service = each.key })
}

# ============================================================================ services

resource "aws_ecs_service" "this" {
  for_each = var.services

  name                    = each.value.service_name
  cluster                 = aws_ecs_cluster.this.id
  task_definition         = aws_ecs_task_definition.this[each.key].arn
  desired_count           = each.value.min_count
  platform_version        = "LATEST"
  enable_execute_command  = var.enable_execute_command
  propagate_tags          = "SERVICE"
  enable_ecs_managed_tags = true

  availability_zone_rebalancing = "ENABLED"

  capacity_provider_strategy {
    capacity_provider = each.value.use_fargate_spot ? "FARGATE_SPOT" : "FARGATE"
    weight            = 1
    base              = 0
  }

  network_configuration {
    subnets          = var.subnet_ids
    security_groups  = [each.value.security_group_id]
    assign_public_ip = false
  }

  dynamic "load_balancer" {
    for_each = each.value.target_group_arn == null ? [] : [1]
    content {
      target_group_arn = each.value.target_group_arn
      container_name   = each.key
      container_port   = each.value.port
    }
  }

  health_check_grace_period_seconds = each.value.target_group_arn == null ? null : each.value.health_check_grace_period

  deployment_controller {
    type = "ECS"
  }

  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  dynamic "service_connect_configuration" {
    for_each = each.value.service_connect == null ? [] : [each.value.service_connect]
    content {
      enabled   = true
      namespace = aws_service_discovery_http_namespace.this.arn

      log_configuration {
        log_driver = "awslogs"
        options = {
          awslogs-group         = each.value.log_group_name
          awslogs-region        = local.region
          awslogs-stream-prefix = "service-connect"
        }
      }

      # server side: only services that publish an endpoint (backend -> backend.pgms.internal:8080)
      dynamic "service" {
        for_each = service_connect_configuration.value.discovery_name == null ? [] : [1]
        content {
          port_name      = "http"
          discovery_name = service_connect_configuration.value.discovery_name

          client_alias {
            port     = each.value.port
            dns_name = "${service_connect_configuration.value.discovery_name}.${var.service_connect_namespace}"
          }
        }
      }
    }
  }

  wait_for_steady_state = false

  tags = merge(var.tags, { Service = each.key })

  lifecycle {
    # task_definition: owned by the CI/CD pipeline after the first apply (image updates)
    # desired_count:   owned by application autoscaling
    ignore_changes = [task_definition, desired_count]
  }

  depends_on = [aws_ecs_cluster_capacity_providers.this]
}

# ============================================================================ autoscaling

resource "aws_appautoscaling_target" "this" {
  for_each = var.services

  service_namespace  = "ecs"
  scalable_dimension = "ecs:service:DesiredCount"
  resource_id        = "service/${aws_ecs_cluster.this.name}/${aws_ecs_service.this[each.key].name}"
  min_capacity       = each.value.min_count
  max_capacity       = each.value.max_count

  tags = var.tags
}

resource "aws_appautoscaling_policy" "cpu" {
  for_each = var.services

  name               = "${var.name_prefix}-${each.key}-cpu"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = aws_appautoscaling_target.this[each.key].service_namespace
  scalable_dimension = aws_appautoscaling_target.this[each.key].scalable_dimension
  resource_id        = aws_appautoscaling_target.this[each.key].resource_id

  target_tracking_scaling_policy_configuration {
    target_value       = each.value.cpu_target_percent
    scale_in_cooldown  = 300
    scale_out_cooldown = 60

    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
  }
}

resource "aws_appautoscaling_policy" "requests" {
  for_each = local.autoscaled_request_policies

  name               = "${var.name_prefix}-${each.key}-requests"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = aws_appautoscaling_target.this[each.key].service_namespace
  scalable_dimension = aws_appautoscaling_target.this[each.key].scalable_dimension
  resource_id        = aws_appautoscaling_target.this[each.key].resource_id

  target_tracking_scaling_policy_configuration {
    target_value       = each.value.requests_per_target
    scale_in_cooldown  = 300
    scale_out_cooldown = 60

    predefined_metric_specification {
      predefined_metric_type = "ALBRequestCountPerTarget"
      resource_label         = "${var.alb_arn_suffix}/${each.value.target_group_arn_suffix}"
    }
  }
}
