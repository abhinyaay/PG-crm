# Terraform: AWS production stack

This directory turns [`docs/AWS_DEPLOYMENT.md`](../../docs/AWS_DEPLOYMENT.md) into infrastructure as code. It covers
one region (`ap-south-1` by default) across three AZs, with a VPC, ALB + WAF, ECS Fargate, RDS PostgreSQL 16,
ElastiCache Redis 7, Secrets Manager, CloudWatch and GitHub OIDC.

> **Status:** validated offline only, with `terraform validate`, `fmt`, `tflint`, `checkov` and a mocked-provider
> `terraform test` plan. It has **not** been applied to a real account yet. Expect small fixes on the first apply.

```
infra/terraform/
├── bootstrap/                 # S3 state bucket + DynamoDB lock table (local state, applied once)
├── envs/prod/                 # production root: composes the modules
│   ├── backend.hcl.example    # partial S3 backend config  -> backend.hcl (git-ignored)
│   ├── terraform.tfvars.example                            -> terraform.tfvars (git-ignored)
│   └── tests/plan.tftest.hcl  # offline plan test with mocked providers
├── modules/
│   ├── kms/            # CMKs: data, secrets, logs
│   ├── network/        # VPC, 3x3 subnets, IGW, NAT, endpoints, flow logs
│   ├── security/       # security groups (doc section 3)
│   ├── data/           # RDS writer + replica, DR backup replication, ElastiCache
│   ├── secrets/        # Secrets Manager secrets (random_password)
│   ├── ecr/            # repositories + lifecycle policies
│   ├── ecs/            # cluster, Service Connect, task defs, services, autoscaling, IAM
│   ├── db-bootstrap/   # one-off Fargate task that creates the DB roles on RDS
│   ├── alb/            # ACM, ALB, listeners/rules, target groups, access logs, WAF
│   ├── observability/  # log groups, SNS topic, alarms
│   └── cicd/           # GitHub OIDC provider + deploy role
├── .tflint.hcl
└── .gitignore
```

## Architecture to module mapping

| AWS_DEPLOYMENT.md | Module | Notes |
|---|---|---|
| §2 VPC `10.20.0.0/16`, 3 AZs, public / private-app / private-data | `network` | Tier blocks are /19 at `10.20.0.0`, `10.20.32.0` and `10.20.64.0`, with one **/21 per AZ**. The doc's "/20 x3" cannot fit at those offsets. You can override with `*_subnet_cidrs`. |
| §2 NAT per AZ | `network` | `nat_gateway_mode = per_az \| single \| none` |
| §2 VPC endpoints | `network` | S3 gateway, plus interface endpoints for `ecr.api`, `ecr.dkr`, `secretsmanager` and `logs` (`enable_interface_endpoints`) |
| §2 data tier has no internet route | `network` | The data route table has local routes only |
| VPC flow logs | `network` | All traffic, sent to a KMS-encrypted log group (90 days) |
| §3 security groups | `security` | Exact SG-to-SG rules from the doc. Adds an SG for the frontend and one for the endpoints. Task egress is 443 to the endpoint SG plus the S3 prefix list (ECR layers). Internet egress is opt-in. The default SG denies everything. |
| §4 ECS cluster `pgms-prod`, Container Insights | `ecs` | Fargate and Fargate Spot capacity providers |
| §4 services, sizing, scaling | `ecs` (wired in `envs/prod/main.tf`) | backend 1 vCPU/2 GB, 2..10 tasks, CPU 60% + ALBRequestCountPerTarget. analytics 0.5/1 GB, 1..3 tasks, CPU 70% + requests. frontend 0.25/0.5 GB, 2..4 tasks. |
| §4 rolling deploy, circuit breaker | `ecs` | min 100% / max 200%, circuit breaker with rollback |
| §4 health checks | `alb`, `ecs` | TG backend `/actuator/health/readiness` (healthy 2, interval 15 s). analytics `/health/ready`. frontend `/healthz`. Container liveness checks are copied from `infra/aws/ecs/*.json`. |
| §4 graceful shutdown | `ecs`, `alb` | `stopTimeout=30`, deregistration delay 30 s, init process enabled |
| §4 listener rules | `alb` | `/api/*` goes to backend and `/analytics/*` to analytics. `/actuator*`, `/swagger-ui*` and `/v3/api-docs*` return a fixed 404. **Default goes to the frontend** (the SPA did not exist when the doc was written). HTTP returns a 301 to HTTPS. TLS 1.2/1.3 policy. |
| §2 Service Connect `backend.pgms.internal:8080` | `ecs` | HTTP namespace. backend is the server and analytics is a client. |
| §5 RDS PostgreSQL 16 | `data` | `db.r6g.large`, Multi-AZ, gp3 with autoscaling, KMS, `rds.force_ssl=1`, `pg_stat_statements`, `log_min_duration_statement=500`, `max_connections` derived from tasks × pool. 14-day PITR, deletion protection, Performance Insights (KMS), Enhanced Monitoring, log exports, IAM auth. RDS manages the master password. |
| §5 read replica | `data` | `create_read_replica`. Analytics `DATABASE_URL` points at it. |
| §5 cross-region DR copy | `data` (`aws.dr` provider) | `enable_cross_region_backups` replicates automated backups (PITR-capable) to `dr_region` |
| §5 roles `pgms_app`, `analytics_ro`, extensions | `db-bootstrap` | A one-off task that does on RDS what `infra/postgres/init/01-roles.sh` does locally. It is idempotent. |
| §6 ElastiCache Redis 7 | `data` | `cache.t4g.small`, primary + replica, automatic failover, Multi-AZ, TLS required, AUTH token, KMS at rest, `volatile-lru`, slow/engine logs |
| §8 Secrets Manager | `secrets` | `pgms/prod/{db-app, db-analytics-ro, redis, analytics, jwt, bootstrap-admin}`. JSON keys match the task-definition templates. |
| §8 IAM | `ecs` | One **execution role per service**: pull its own repo, write its own logs, read only its own secrets, KMS decrypt via Secrets Manager only. Task roles have no permissions (ECS Exec adds `ssmmessages` only if enabled). |
| §1 ECR | `ecr` | `pgms-backend`, `pgms-analytics`, `pgms-frontend` (+ `pgms-ops` for the psql image). Immutable tags, scan on push, KMS, lifecycle (untagged > 7 d, keep last 30). |
| §1 WAF | `alb` | IP reputation, per-IP rate limit (3000/5 min), auth-path rate limit (100/5 min, returns 429), Common rule set (`SizeRestrictions_BODY` set to count), Known Bad Inputs (Log4j), SQLi. Logs go to CloudWatch with `authorization`/`cookie` redacted. |
| §1 ALB access logs | `alb` | S3 bucket (SSE-S3, which ELB requires), TLS-only policy, lifecycle 30 d to IA, expiry 90 d |
| §7 logs | `observability` | `/pgms/prod/{backend,analytics,frontend}`, 30 days, KMS |
| §7 alarms to SNS | `observability` | Target 5xx > 1 % (5 min), p95 > 1 s, backend healthy hosts < 2, RDS CPU / connections > 80 % of max / replica lag > 60 s / free storage < 15 %, Redis evictions (sustained) / engine CPU, ERROR-log rate per service. KMS-encrypted topic, optional e-mail subscriptions. |
| §1 CI/CD identity | `cicd` | GitHub OIDC provider and deploy role, trusted only for `repo:<owner>/<repo>:ref:refs/heads/main` and `...:environment:production` |

## What you must fill in

| Item | Where |
|---|---|
| AWS account ID (the provider refuses any other account) | `terraform.tfvars` → `aws_account_id` |
| Public hostname (SPA + API on one origin) | `domain_name` (+ `subject_alternative_names`) |
| DNS: Route 53 zone **or** manual validation | `route53_zone_id` (auto-validates ACM and creates alias records) or create the records from the `certificate_validation_records` output, then CNAME the domain to `alb_dns_name`. You can also supply `certificate_arn`. |
| GitHub repository | `github_repository = "owner/repo"` |
| Alarm recipients | `alarm_email_addresses` (each recipient must confirm the SNS e-mail). Hook Slack/PagerDuty to `alarm_topic_arn`. |
| Initial image tags | `image_tags` (12-char commit SHA, matching CI) |
| Existing GitHub OIDC provider? | `create_github_oidc_provider = false` + `existing_github_oidc_provider_arn` |
| State backend | `envs/prod/backend.hcl` from the bootstrap output |
| JWT key pair | Written out-of-band into `pgms/prod/jwt` (see below) |

## Bootstrap remote state

```bash
cd infra/terraform/bootstrap
terraform init
terraform apply -var region=ap-south-1          # creates bucket pgms-terraform-state-<account>, table pgms-terraform-locks
terraform output -raw backend_config > ../envs/prod/backend.hcl
```

Bootstrap keeps its own state locally (`bootstrap/terraform.tfstate`, git-ignored). Keep that file safe, or migrate
it into the bucket: add a `backend "s3" {}` block, then run `terraform init -migrate-state -backend-config=...` with
`key = "bootstrap/terraform.tfstate"`. The bucket and table have `prevent_destroy` set.

## First deployment (apply order)

Run every step from `infra/terraform/envs/prod` with admin credentials for the target account.

```bash
cp terraform.tfvars.example terraform.tfvars    # fill in
terraform init -backend-config=backend.hcl
```

1. **Repositories.** Run `terraform apply -target=module.ecr`.
2. **Initial images.** Build and push the three images, tagged with the 12-char commit SHA that you put in
   `image_tags`. Mirror psql for the DB bootstrap task:
   ```bash
   REG=<account>.dkr.ecr.ap-south-1.amazonaws.com; TAG=$(git rev-parse --short=12 HEAD)
   aws ecr get-login-password | docker login --username AWS --password-stdin $REG
   for s in backend analytics frontend; do
     ctx=$([ $s = analytics ] && echo analytics-service || echo $s)
     docker build --platform linux/amd64 -t $REG/pgms-$s:$TAG ../../../../$ctx && docker push $REG/pgms-$s:$TAG
   done
   docker pull --platform linux/amd64 postgres:16-alpine
   docker tag postgres:16-alpine $REG/pgms-ops:postgres-16-alpine && docker push $REG/pgms-ops:postgres-16-alpine
   ```
3. **Data tier, secrets and DB bootstrap task.** Run
   `terraform apply -target=module.secrets -target=module.db_bootstrap`. This brings up the network, KMS, RDS
   (about 20 to 40 minutes), Redis and the secrets.
4. **JWT key pair.** The private key never enters Terraform state:
   ```bash
   eval "$(../../../../scripts/generate-jwt-keys.sh | grep -v ALLOW)"   # APP_JWT_PRIVATE_KEY / APP_JWT_PUBLIC_KEY
   aws secretsmanager put-secret-value --secret-id pgms/prod/jwt \
     --secret-string "$(jq -n --arg p "$APP_JWT_PRIVATE_KEY" --arg u "$APP_JWT_PUBLIC_KEY" '{private_key:$p,public_key:$u}')"
   ```
5. **DB roles.** These must exist before the backend runs Flyway, because migration V2 grants to `analytics_ro`.
   Run `$(terraform output -raw db_bootstrap_run_task_command)`. Then check `/pgms/prod/db-bootstrap` for
   `db-bootstrap: roles and extensions are in place`.
6. **Everything else.** Run a full `terraform apply`. The ALB, WAF, ECS services, alarms and deploy role are created.
   The backend starts with Flyway, then analytics and the frontend.
7. **DNS and alarms.** If DNS is not in Route 53, create the validation and alias/CNAME records. Confirm the SNS
   e-mail subscriptions.
8. **GitHub.** Set the secret `AWS_DEPLOY_ROLE_ARN` from `terraform output -raw deploy_role_arn`. Set the repository
   variables (next section), including `AWS_DEPLOY_ENABLED=true`, which turns on the push and deploy steps in
   `ci-cd.yml`. In *Settings → Environments → production*, require reviewers and limit deployment
   branches to `main`.
9. **After the first SUPER_ADMIN has logged in.** Set `enable_bootstrap_admin = false`, run `apply`, then roll out
   the new revision right away (see "Day-2 operations"). Running tasks still reference the bootstrap secret, which is
   now scheduled for deletion.

`-target` is used only for this one-time ordering. Later applies are always full applies.

## How CI uses the outputs

Terraform owns the task definition **shape**: environment, secrets, sizing, health checks, roles and logging. CI
owns the **image**:

* `aws_ecs_service` ignores `task_definition` and `desired_count`. Deployments come from CI, and autoscaling owns
  the count.
* The deploy job reads the **latest revision of each family** (`aws ecs describe-task-definition`), swaps the image,
  registers it and deploys it. Then it writes the deployed tag to SSM: `/pgms/prod/image-tag/<service>`.
* Terraform builds image references from those SSM parameters. Value changes are ignored, but Terraform refreshes
  them. So every revision Terraform registers runs the image that is currently deployed, never a stale bootstrap tag.
* The static `infra/aws/ecs/*.json` templates are superseded. Their values now live in `envs/prod/main.tf`.

Publish the outputs as GitHub repository variables once, and again whenever they change:

```bash
terraform output -json github_actions_variables \
  | jq -r 'to_entries[] | "\(.key)\t\(.value)"' \
  | while IFS=$'\t' read -r k v; do gh variable set "$k" --body "$v"; done
gh secret set AWS_DEPLOY_ROLE_ARN --body "$(terraform output -raw deploy_role_arn)"
```

Variables: `AWS_REGION`, `ECS_CLUSTER`, `ECS_{BACKEND,ANALYTICS,FRONTEND}_SERVICE`,
`ECS_{BACKEND,ANALYTICS,FRONTEND}_TASK_FAMILY`, `IMAGE_TAG_PARAMETER_PATH`, `PUBLIC_BASE_URL`.

The deploy role can do only these things:

* push to the three application repositories (not `pgms-ops`)
* describe and register task definitions
* update the three services
* pass the six platform roles to `ecs-tasks.amazonaws.com`. Registering a task definition passes **both** the task
  role and the execution role, so the execution roles are included.
* write the three image-tag parameters

## Day-2 operations

* **Change env, secrets or sizing.** Run `terraform apply`. This registers a new revision with the current image.
  Roll it out with the next CI deploy, or right away:
  `aws ecs update-service --cluster pgms-prod --service pgms-backend --task-definition pgms-prod-backend`.
  This is safe because the latest revision carries the deployed image.
* **Re-sync DB role passwords** after changing `db-app` / `db-analytics-ro`: re-run the db-bootstrap task, then
  force a new deployment of backend and analytics.
* **Break-glass shell.** Set `enable_execute_command = true` (it adds `ssmmessages` to the task roles), then run
  `aws ecs execute-command`. With `nat_gateway_mode = "none"` you also need an `ssmmessages` interface endpoint.
* **Validation (offline)**, as run for this change:
  ```bash
  docker run --rm -v "$PWD:/w" -w /w hashicorp/terraform:1.9 fmt -check -recursive
  for r in bootstrap envs/prod; do
    docker run --rm -v "$PWD:/w" -w /w/$r hashicorp/terraform:1.9 init -backend=false
    docker run --rm -v "$PWD:/w" -w /w/$r hashicorp/terraform:1.9 validate
  done
  docker run --rm -v "$PWD:/w" -w /w/envs/prod hashicorp/terraform:1.9 test          # mocked-provider plan
  docker run --rm -v "$PWD:/d" -w /d --entrypoint sh ghcr.io/terraform-linters/tflint \
    -c 'tflint --init --config /d/.tflint.hcl && tflint --recursive --config /d/.tflint.hcl'
  docker run --rm -v "$PWD:/tf" bridgecrew/checkov -d /tf --framework terraform --quiet --compact
  ```

## Cost notes (rough, on-demand, ap-south-1, before data transfer)

| Item | ~USD / month |
|---|---|
| RDS `db.r6g.large` Multi-AZ writer + 100 GB gp3 | 330–380 |
| RDS read replica `db.r6g.large` | 165–190 |
| ElastiCache 2 × `cache.t4g.small` | ~50 |
| NAT gateways, 3 × per-AZ (+ $/GB processed) | ~100–125 |
| Interface endpoints, 4 services × 3 AZs | ~90–100 |
| ALB (+ LCUs) | ~20–30 |
| WAF (ACL + 6 rules + requests) | ~12–20 |
| Fargate: backend 2 × 1 vCPU/2 GB, analytics 0.5/1 GB, frontend 2 × 0.25/0.5 GB | ~110 |
| KMS (3–4 keys), Secrets (6), CloudWatch logs/alarms/Container Insights | ~30–60 |
| **Total (baseline, min task counts)** | **~900–1,100** |

These figures are estimates. Check them in the AWS Pricing Calculator.

Levers, in the order given in the doc:

* `analytics_use_fargate_spot = true`
* `nat_gateway_mode = "single"` (saves about $70) or `"none"` (endpoints only, no outbound internet)
* smaller `db_instance_class` / `db_replica_instance_class`, or `create_read_replica = false` (about −$180; analytics
  then reads the writer)
* Compute Savings Plans for the steady backend
* `enable_interface_endpoints = false` with NAT. This is cheaper at low traffic but less private, and it opens tcp/443
  egress.

## Limitations and things not covered

* **Not applied to a real account.** Everything was validated offline. AWS-side constraints, such as quotas, a
  regional `engine_version` or WAF capacity, show up only on the first apply.
* **Secret rotation.** RDS rotates the master secret. The application secrets have no rotation Lambda (checkov
  `CKV2_AWS_57` is suppressed with a reason). Rotating `db-app`, `db-analytics-ro` or `redis` means:
  * change the secret (or `terraform apply -replace=module.secrets.random_password.<x>`);
  * re-run db-bootstrap;
  * redeploy.

  A Redis AUTH rotation goes through the `ROTATE` strategy.
* **Generated passwords live in Terraform state.** The state is encrypted with KMS and access to the bucket must be
  restricted. The JWT private key is kept out of state on purpose.
* **DB roles** are created by a manually run one-off task (`db-bootstrap`), not automatically.
* **TLS to RDS** uses `sslmode=require` (encrypted, server certificate not verified). `verify-full` needs the RDS CA
  bundle inside the images.
* **`readonlyRootFilesystem` is off.** Fargate bind mounts are root-owned (0755) unless the image declares
  `VOLUME`, so `/tmp` (Java, Python) and `/var/cache/nginx` would not be writable for the non-root users. To enable
  it, add `VOLUME ["/tmp"]` (frontend: also `/var/cache/nginx`) to the Dockerfiles, then add volumes and mount
  points to the task definitions. checkov does not flag this because it cannot read the `jsonencode`d container
  definitions.
* **Frontend on port 80 as non-root** relies on the `net.ipv4.ip_unprivileged_port_start=0` system control (Fargate
  supports namespaced `net.*` sysctls). If RegisterTaskDefinition rejects it, set
  `frontend_unprivileged_port_sysctl = false` and move nginx to `listen 8080` (`frontend_port`).
* **Not implemented:**
  * RDS Proxy
  * CloudWatch Logs export to S3 (needs a subscription filter + Firehose)
  * X-Ray / OpenTelemetry
  * a Prometheus scraper
  * Route 53 hosted zone creation
  * an automated DR restore (only the backups are replicated)
* **Single environment root.** To add staging, copy `envs/prod` to `envs/staging` and set
  `environment = "staging"`, a different `backend.hcl` key, smaller sizes and `service_connect_namespace`. Note:
  * In the same account, set `create_github_oidc_provider = false`.
  * The ECR repository names are account-wide (parameterise `local.repositories` or share the prod repositories).
* **WAF** omits `AWSManagedRulesAnonymousIpList` on purpose (residents and staff on VPN / iCloud Private Relay).
  checkov `CKV2_AWS_76` is suppressed for this reason. Known Bad Inputs covers Log4j.
* **Alarms.** The free-storage threshold is based on the *initial* allocated storage, so recompute it after storage
  autoscaling grows the volume. The 5xx-rate alarm ignores minutes with fewer than 20 requests.
