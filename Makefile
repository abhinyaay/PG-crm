# PG Management Platform - operations shortcuts.  Run `make` (or `make help`) for the list.
# Everything targets the compose project "pgms" only; nothing here touches other Docker projects on the host.

SHELL := /bin/bash
.DEFAULT_GOAL := help

PROJECT  := pgms
COMPOSE  := docker compose -p $(PROJECT) -f docker-compose.yml
# app + observability add-on. GRAFANA_ADMIN_PASSWORD is only needed when grafana is (re)created; the placeholder lets
# ps/logs/down work before observability was ever set up (a real value in .env or the shell still wins).
COMPOSE_ALL := GRAFANA_ADMIN_PASSWORD="$${GRAFANA_ADMIN_PASSWORD:-$$(grep -E '^GRAFANA_ADMIN_PASSWORD=' .env 2>/dev/null | tail -n1 | cut -d= -f2-)}"; \
               GRAFANA_ADMIN_PASSWORD="$${GRAFANA_ADMIN_PASSWORD:-not-set}" \
               docker compose -p $(PROJECT) -f docker-compose.yml -f docker-compose.observability.yml

# read a value from .env without exporting the whole file (it contains multi-line keys)
env_get = $(shell grep -E '^$(1)=' .env 2>/dev/null | tail -n1 | cut -d= -f2- | sed -E 's/^"(.*)"$$/\1/')
NGINX_PORT := $(or $(call env_get,NGINX_HOST_PORT),80)
BASE_URL   ?= http://localhost:$(NGINX_PORT)
PROM_PORT  := $(or $(call env_get,PROMETHEUS_HOST_PORT),58301)
GRAF_PORT  := $(or $(call env_get,GRAFANA_HOST_PORT),58302)

S ?=   # optional service name for logs, e.g. `make logs S=backend`

.PHONY: help up down logs ps health smoke e2e test-backend test-analytics test-frontend test \
        observability-up observability-down observability-check backup restore-check loadtest keys

help: ## Show this help
	@awk 'BEGIN {FS = ":.*?## "; printf "Usage: make <target>\n\n"} /^[a-zA-Z0-9_-]+:.*?## / {printf "  \033[36m%-20s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)
	@echo
	@echo "  Edge: $(BASE_URL)   Prometheus: http://127.0.0.1:$(PROM_PORT)   Grafana: http://127.0.0.1:$(GRAF_PORT)"

## ---------------------------------------------------------------- lifecycle
up: ## Build and start the application stack (postgres, redis, mailpit, backend, analytics, frontend, nginx)
	$(COMPOSE) up -d --build
	@echo "Waiting for nginx /health ..."; for i in $$(seq 1 60); do \
	  curl -fsS -o /dev/null "$(BASE_URL)/health" && { echo "up: $(BASE_URL)"; exit 0; }; sleep 3; done; \
	  echo "stack did not become healthy - see 'make ps' and 'make logs'"; exit 1

down: ## Stop and remove pgms containers + network (volumes/data are KEPT; other projects untouched)
	$(COMPOSE_ALL) down

logs: ## Follow logs (all pgms services, or one: make logs S=backend)
	$(COMPOSE_ALL) logs -f --tail=200 $(S)

ps: ## Show pgms containers and their health
	$(COMPOSE_ALL) ps

health: ## Read-only health report: containers, probes, postgres, redis, outbox, prometheus targets
	@./scripts/health-check.sh

## ---------------------------------------------------------------- tests
smoke: ## End-to-end smoke test through nginx (38 checks; needs the demo profile)
	@DEMO_PASSWORD="$$(grep -E '^APP_DEMO_PASSWORD=' .env | tail -n1 | cut -d= -f2-)" BASE_URL="$(BASE_URL)" ./scripts/smoke-test.sh

e2e: ## Browser e2e (frontend `npm run e2e`) if defined, otherwise health report + API smoke test
	@if jq -e '.scripts.e2e' frontend/package.json >/dev/null 2>&1; then \
	  cd frontend && BASE_URL="$(BASE_URL)" npm run e2e; \
	else \
	  echo "frontend has no e2e script yet - running health + API smoke instead"; \
	  $(MAKE) --no-print-directory health && $(MAKE) --no-print-directory smoke; \
	fi

test-backend: ## Backend: Checkstyle, unit + Testcontainers integration tests, JaCoCo gate (mvn verify; needs Docker)
	cd backend && mvn -B -ntp verify

test-analytics: ## Analytics: ruff + pytest with 90% coverage gate (in a python:3.12 container)
	docker run --rm -v "$(CURDIR)/analytics-service:/src:ro" -v pgms-pip-cache:/root/.cache/pip \
	  -w /work python:3.12-slim sh -c 'cp -r /src/. /work && rm -rf .venv && \
	    pip install -q --disable-pip-version-check -r requirements.txt -r requirements-dev.txt && \
	    ruff check . && ruff format --check . && pytest -q --cov=app --cov-fail-under=90'

test-frontend: ## Frontend: eslint, typecheck, vitest (installs node_modules only if missing)
	cd frontend && { [ -d node_modules ] || npm ci --no-audit --no-fund; } && npm run lint && npm run typecheck && npm test

test: test-backend test-analytics test-frontend ## All unit/integration suites

## ---------------------------------------------------------------- observability
observability-up: ## Start Prometheus (127.0.0.1:58301) + Grafana (127.0.0.1:58302) without touching app containers
	@./scripts/observability-env.sh
	$(COMPOSE_ALL) up -d --no-deps prometheus grafana
	@echo "Prometheus: http://127.0.0.1:$(PROM_PORT)/targets"
	@echo "Grafana:    http://127.0.0.1:$(GRAF_PORT)/d/pgms-backend/pgms-backend  (admin / GRAFANA_ADMIN_PASSWORD in .env)"

observability-down: ## Stop and remove only the Prometheus and Grafana containers (their data volumes are kept)
	$(COMPOSE_ALL) rm -sf prometheus grafana

observability-check: ## Validate prometheus.yml + alert rules with promtool
	docker run --rm --entrypoint promtool -v "$(CURDIR)/infra/observability/prometheus:/etc/prometheus:ro" \
	  prom/prometheus:v2.55.1 check config /etc/prometheus/prometheus.yml

## ---------------------------------------------------------------- data
backup: ## pg_dump (custom format) into ./backups/, prune dumps older than BACKUP_RETENTION_DAYS (14)
	./scripts/backup-db.sh

restore-check: ## Restore the latest backup into scratch DB pgms_restore_check, verify row counts, drop it
	./scripts/restore-db.sh --yes --drop-after

## ---------------------------------------------------------------- performance
loadtest: ## k6 (docker) read mix + bed-contention test, ~45s, <=8 VUs. Tune with RATE=.. DURATION=..
	./scripts/loadtest/run.sh

## ---------------------------------------------------------------- security
keys: ## Generate a new RS256 JWT key pair into run-output/ (does NOT edit .env; see docs/OPERATIONS.md)
	@mkdir -p run-output && umask 077 && f="run-output/jwt-keys-$$(date -u +%Y%m%dT%H%M%SZ).env" && \
	  ./scripts/generate-jwt-keys.sh > "$$f" && \
	  echo "Wrote $$f (mode 600). To rotate: replace the APP_JWT_* lines in .env with its contents, then" && \
	  echo "  docker compose -p $(PROJECT) up -d --no-deps backend && docker compose -p $(PROJECT) restart analytics" && \
	  echo "(access tokens issued with the old key stop working; clients recover via their refresh token)" && \
	  echo "Delete $$f afterwards."
