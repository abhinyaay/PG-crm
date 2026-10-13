###############################################################################
# Production composition of the PG management platform (docs/AWS_DEPLOYMENT.md)
###############################################################################

locals {
  name_prefix        = "${var.project}-${var.environment}"
  secret_path_prefix = "${var.project}/${var.environment}"

  # Application images built and pushed by CI.
  repositories = {
    backend   = "${var.project}-backend"
    analytics = "${var.project}-analytics"
    frontend  = "${var.project}-frontend"
  }
  # Operational images mirrored by an operator (psql for the one-off db-bootstrap task); CI has no access.
  ops_repository = "${var.project}-ops"

  # max backend tasks x Hikari pool, plus headroom for the replica-free writer: migrations, ShedLock,
  # admin sessions and a rolling deployment (old + new tasks briefly overlap).
  db_max_connections = coalesce(var.db_max_connections, var.backend_max_count * var.db_pool_size * 2 + 50)

  secret_arns = module.secrets.secret_arns

  backend_bootstrap_secrets = var.enable_bootstrap_admin ? {
    APP_BOOTSTRAP_SUPER_ADMIN_EMAIL    = "${local.secret_arns["bootstrap-admin"]}:email::"
    APP_BOOTSTRAP_SUPER_ADMIN_PASSWORD = "${local.secret_arns["bootstrap-admin"]}:password::"
  } : {}

  public_origin = "https://${var.domain_name}"
}

# ============================================================================ foundation

module "kms" {
  source = "../../modules/kms"

  name_prefix = local.name_prefix
}

module "network" {
  source = "../../modules/network"

  name_prefix                = local.name_prefix
  environment                = var.environment
  vpc_cidr                   = var.vpc_cidr
  azs                        = var.azs
  nat_gateway_mode           = var.nat_gateway_mode
  enable_interface_endpoints = var.enable_interface_endpoints
  logs_kms_key_arn           = module.kms.logs_key_arn
}

module "security" {
  source = "../../modules/security"

  name_prefix                    = local.name_prefix
  vpc_id                         = module.network.vpc_id
  endpoints_security_group_id    = module.network.endpoints_security_group_id
  s3_prefix_list_id              = module.network.s3_prefix_list_id
  allow_https_egress_to_internet = var.allow_https_egress_to_internet || !var.enable_interface_endpoints
}

module "ecr" {
  source = "../../modules/ecr"

  repository_names = concat(values(local.repositories), [local.ops_repository])
  kms_key_arn      = module.kms.data_key_arn
}

# ============================================================================ data + secrets

module "secrets" {
  source = "../../modules/secrets"

  secret_path_prefix            = local.secret_path_prefix
  kms_key_arn                   = module.kms.secrets_key_arn
  db_name                       = var.db_name
  db_writer_address             = module.data.db_writer_address
  db_reader_address             = module.data.db_reader_address
  redis_primary_endpoint        = module.data.redis_primary_endpoint
  create_bootstrap_admin_secret = var.enable_bootstrap_admin
  bootstrap_admin_email         = var.bootstrap_admin_email
}

module "data" {
  source = "../../modules/data"

  providers = {
    aws    = aws
    aws.dr = aws.dr
  }

  name_prefix             = local.name_prefix
  environment             = var.environment
  data_subnet_ids         = module.network.data_subnet_ids
  rds_security_group_id   = module.security.rds_security_group_id
  redis_security_group_id = module.security.redis_security_group_id
  data_kms_key_arn        = module.kms.data_key_arn
  secrets_kms_key_arn     = module.kms.secrets_key_arn
  logs_kms_key_arn        = module.kms.logs_key_arn
  log_retention_days      = var.log_retention_days
  deletion_protection     = var.deletion_protection

  db_name                   = var.db_name
  db_instance_class         = var.db_instance_class
  db_replica_instance_class = var.db_replica_instance_class
  db_allocated_storage      = var.db_allocated_storage
  db_max_allocated_storage  = var.db_max_allocated_storage
  db_max_connections        = local.db_max_connections
  create_read_replica       = var.create_read_replica

  enable_cross_region_backups = var.enable_cross_region_backups

  redis_node_type          = var.redis_node_type
  redis_num_cache_clusters = var.redis_num_cache_clusters
  redis_auth_token         = module.secrets.redis_auth_token
}

# One-off task that creates the pgms_app / analytics_ro roles on RDS (README: first deployment).
module "db_bootstrap" {
  source = "../../modules/db-bootstrap"

  name_prefix             = local.name_prefix
  environment             = var.environment
  image                   = "${module.ecr.repository_urls[local.ops_repository]}:${var.db_bootstrap_image_tag}"
  repository_arn          = module.ecr.repository_arns[local.ops_repository]
  db_host                 = module.data.db_writer_address
  db_name                 = var.db_name
  db_master_username      = module.data.db_master_username
  master_secret_arn       = module.data.db_master_user_secret_arn
  app_db_secret_arn       = module.secrets.secret_arns["db-app"]
  analytics_db_secret_arn = module.secrets.secret_arns["db-analytics-ro"]
  secrets_kms_key_arn     = module.kms.secrets_key_arn
  logs_kms_key_arn        = module.kms.logs_key_arn
  log_retention_days      = var.log_retention_days
}

# ============================================================================ edge

module "alb" {
  source = "../../modules/alb"

  name_prefix               = local.name_prefix
  vpc_id                    = module.network.vpc_id
  public_subnet_ids         = module.network.public_subnet_ids
  alb_security_group_id     = module.security.alb_security_group_id
  domain_name               = var.domain_name
  subject_alternative_names = var.subject_alternative_names
  certificate_arn           = var.certificate_arn
  route53_zone_id           = var.route53_zone_id
  deletion_protection       = var.deletion_protection
  logs_kms_key_arn          = module.kms.logs_key_arn
  waf_log_retention_days    = var.log_retention_days

  waf_rate_limit_per_5min      = var.waf_rate_limit_per_5min
  waf_auth_rate_limit_per_5min = var.waf_auth_rate_limit_per_5min
}

# ============================================================================ observability

module "observability" {
  source = "../../modules/observability"

  name_prefix           = local.name_prefix
  environment           = var.environment
  account_id            = var.aws_account_id
  log_retention_days    = var.log_retention_days
  logs_kms_key_arn      = module.kms.logs_key_arn
  alarm_email_addresses = var.alarm_email_addresses

  alb_arn_suffix                  = module.alb.alb_arn_suffix
  backend_target_group_arn_suffix = module.alb.target_group_arn_suffixes["backend"]
  backend_min_healthy_hosts       = min(2, var.backend_min_count)

  db_writer_identifier     = module.data.db_writer_identifier
  db_replica_identifier    = var.create_read_replica ? "${local.name_prefix}-postgres-replica" : null
  db_max_connections       = local.db_max_connections
  db_allocated_storage_gib = var.db_allocated_storage
  redis_member_cluster_ids = module.data.redis_member_cluster_ids
}

# ============================================================================ compute

module "ecs" {
  source = "../../modules/ecs"

  name_prefix                = local.name_prefix
  cluster_name               = local.name_prefix
  container_insights         = var.container_insights
  service_connect_namespace  = var.service_connect_namespace
  image_tag_parameter_prefix = "/${var.project}/${var.environment}/image-tag"
  subnet_ids                 = module.network.app_subnet_ids
  secrets_kms_key_arn        = module.kms.secrets_key_arn
  alb_arn_suffix             = module.alb.alb_arn_suffix
  enable_execute_command     = var.enable_execute_command

  services = {
    backend = {
      service_name   = "${var.project}-backend"
      repository_url = module.ecr.repository_urls[local.repositories.backend]
      repository_arn = module.ecr.repository_arns[local.repositories.backend]
      image_tag      = var.image_tags.backend
      cpu            = 1024
      memory         = 2048
      port           = 8080

      environment = {
        SPRING_PROFILES_ACTIVE       = "prod"
        DB_URL                       = "jdbc:postgresql://${module.data.db_writer_address}:5432/${var.db_name}?sslmode=require"
        DB_USERNAME                  = "pgms_app"
        DB_POOL_SIZE                 = tostring(var.db_pool_size)
        REDIS_HOST                   = module.data.redis_primary_endpoint
        REDIS_SSL                    = "true"
        APP_JWT_ALLOW_EPHEMERAL_KEYS = "false"
        APP_CORS_ORIGINS             = join(",", concat([local.public_origin], var.extra_cors_origins))
        APP_TIMEZONE                 = var.app_timezone
        API_DOCS_ENABLED             = "false"
      }

      secrets = merge(
        {
          DB_PASSWORD         = "${local.secret_arns["db-app"]}:password::"
          REDIS_PASSWORD      = "${local.secret_arns["redis"]}:auth_token::"
          APP_JWT_PRIVATE_KEY = "${local.secret_arns["jwt"]}:private_key::"
          APP_JWT_PUBLIC_KEY  = "${local.secret_arns["jwt"]}:public_key::"
        },
        local.backend_bootstrap_secrets,
      )
      secret_arns = concat(
        [local.secret_arns["db-app"], local.secret_arns["redis"], local.secret_arns["jwt"]],
        var.enable_bootstrap_admin ? [local.secret_arns["bootstrap-admin"]] : [],
      )

      log_group_name          = module.observability.log_group_names["backend"]
      security_group_id       = module.security.backend_security_group_id
      target_group_arn        = module.alb.target_group_arns["backend"]
      target_group_arn_suffix = module.alb.target_group_arn_suffixes["backend"]
      # 90 s container start period + Flyway on first start of a new version
      health_check_grace_period = 120
      stop_timeout              = 30

      health_check = {
        command      = ["CMD-SHELL", "wget -q -O /dev/null http://127.0.0.1:8080/actuator/health/liveness || exit 1"]
        interval     = 15
        timeout      = 5
        retries      = 3
        start_period = 90
      }

      # Publishes backend.<namespace>:8080 for the analytics JWKS fetch.
      service_connect = { discovery_name = "backend" }

      min_count           = var.backend_min_count
      max_count           = var.backend_max_count
      cpu_target_percent  = 60
      requests_per_target = var.backend_requests_per_target
    }

    analytics = {
      service_name   = "${var.project}-analytics"
      repository_url = module.ecr.repository_urls[local.repositories.analytics]
      repository_arn = module.ecr.repository_arns[local.repositories.analytics]
      image_tag      = var.image_tags.analytics
      cpu            = 512
      memory         = 1024
      port           = 8000

      environment = {
        JWKS_URL          = "http://backend.${var.service_connect_namespace}:8080/api/v1/auth/jwks"
        JWT_ISSUER        = var.jwt_issuer
        JWT_AUDIENCE      = var.jwt_audience
        BUSINESS_TIMEZONE = var.app_timezone
        CACHE_TTL_SECONDS = "300"
        LOG_LEVEL         = "INFO"
      }

      secrets = {
        DATABASE_URL = "${local.secret_arns["analytics"]}:database_url::"
        REDIS_URL    = "${local.secret_arns["analytics"]}:redis_url::"
      }
      secret_arns = [local.secret_arns["analytics"]]

      log_group_name            = module.observability.log_group_names["analytics"]
      security_group_id         = module.security.analytics_security_group_id
      target_group_arn          = module.alb.target_group_arns["analytics"]
      target_group_arn_suffix   = module.alb.target_group_arn_suffixes["analytics"]
      health_check_grace_period = 60
      use_fargate_spot          = var.analytics_use_fargate_spot

      health_check = {
        command      = ["CMD-SHELL", "python -c \"import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8000/health/live',timeout=4).status==200 else 1)\""]
        interval     = 30
        timeout      = 5
        retries      = 3
        start_period = 20
      }

      # Client only: resolves backend.<namespace> through the Service Connect proxy.
      service_connect = {}

      min_count           = var.analytics_min_count
      max_count           = var.analytics_max_count
      cpu_target_percent  = 70
      requests_per_target = var.analytics_requests_per_target
    }

    frontend = {
      service_name   = "${var.project}-frontend"
      repository_url = module.ecr.repository_urls[local.repositories.frontend]
      repository_arn = module.ecr.repository_arns[local.repositories.frontend]
      image_tag      = var.image_tags.frontend
      cpu            = 256
      memory         = 512
      port           = 80

      log_group_name            = module.observability.log_group_names["frontend"]
      security_group_id         = module.security.frontend_security_group_id
      target_group_arn          = module.alb.target_group_arns["frontend"]
      target_group_arn_suffix   = module.alb.target_group_arn_suffixes["frontend"]
      health_check_grace_period = 30

      # nginx runs as uid 101 and binds :80. Docker sets this sysctl by default; Fargate does not
      # guarantee it, so set it explicitly (namespaced net.* sysctl, task-scoped in awsvpc mode).
      system_controls = var.frontend_unprivileged_port_sysctl ? { "net.ipv4.ip_unprivileged_port_start" = "0" } : {}

      health_check = {
        command      = ["CMD-SHELL", "wget -q -O /dev/null http://127.0.0.1/healthz || exit 1"]
        interval     = 15
        timeout      = 3
        retries      = 3
        start_period = 10
      }

      min_count           = var.frontend_min_count
      max_count           = var.frontend_max_count
      cpu_target_percent  = 60
      requests_per_target = var.frontend_requests_per_target
    }
  }
}

# ============================================================================ CI/CD

module "cicd" {
  source = "../../modules/cicd"

  account_id                 = var.aws_account_id
  region                     = var.region
  role_name                  = "${local.name_prefix}-github-deploy"
  github_repository          = var.github_repository
  github_branch              = var.github_branch
  github_environment         = var.github_environment
  create_oidc_provider       = var.create_github_oidc_provider
  existing_oidc_provider_arn = var.existing_github_oidc_provider_arn

  ecr_repository_arns      = [for r in values(local.repositories) : module.ecr.repository_arns[r]]
  task_definition_families = values(module.ecs.task_definition_families)
  ecs_service_arns         = values(module.ecs.service_arns)
  image_tag_parameter_arns = values(module.ecs.image_tag_parameter_arns)
  pass_role_arns           = concat(values(module.ecs.task_role_arns), values(module.ecs.execution_role_arns))
}
