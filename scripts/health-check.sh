#!/usr/bin/env bash
# One-shot health report for the running pgms stack (read-only; changes nothing).
#   ./scripts/health-check.sh        (or: make health)
# Exit code 0 = all critical checks pass, 1 = something critical is down. Warnings don't fail the run.
set -uo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
NETWORK=${PGMS_NETWORK:-pgms_default}
CURL_IMAGE=${CURL_IMAGE:-curlimages/curl:8.10.1}
env_get() { { grep -E "^$1=" "$ROOT/.env" 2>/dev/null || true; } | tail -n1 | cut -d= -f2- | sed -E 's/^"(.*)"$/\1/'; }
NGINX_PORT=$(env_get NGINX_HOST_PORT); NGINX_PORT=${NGINX_PORT:-80}

FAILS=0; WARNS=0
ok()   { printf '  \033[32mOK\033[0m    %s\n' "$*"; }
warn() { printf '  \033[33mWARN\033[0m  %s\n' "$*"; WARNS=$((WARNS + 1)); }
bad()  { printf '  \033[31mFAIL\033[0m  %s\n' "$*"; FAILS=$((FAILS + 1)); }

echo "== Containers (compose project pgms)"
rows=$(docker ps -a --filter label=com.docker.compose.project=pgms --format '{{.Names}}|{{.State}}|{{.Status}}' | sort)
[[ -n "$rows" ]] || { bad "no pgms containers found (make up)"; echo; echo "Failed: $FAILS"; exit 1; }
while IFS='|' read -r name state status; do
  case $name in pgms-k6-*) continue ;; esac
  if [[ "$state" != "running" ]]; then
    case $name in pgms-prometheus-*|pgms-grafana-*) warn "$name: $status" ;; *) bad "$name: $status" ;; esac
  elif [[ "$status" == *"(unhealthy)"* ]]; then bad "$name: $status"
  elif [[ "$status" == *"(health: starting)"* ]]; then warn "$name: $status"
  else ok "$name: $status"; fi
done <<< "$rows"

running() { docker ps --filter "name=^$1$" --filter status=running -q | grep -q .; }

echo "== Edge (host)"
code=$(curl -s -o /dev/null -m 5 -w '%{http_code}' "http://localhost:$NGINX_PORT/health" || echo 000)
[[ "$code" == 200 ]] && ok "nginx http://localhost:$NGINX_PORT/health -> $code" || bad "nginx http://localhost:$NGINX_PORT/health -> $code"
code=$(curl -s -o /dev/null -m 5 -w '%{http_code}' "http://localhost:$NGINX_PORT/actuator/prometheus" || echo 000)
[[ "$code" == 404 ]] && ok "actuator is not exposed at the edge ($code)" || warn "/actuator/prometheus at the edge returned $code (expected 404)"

echo "== Services (inside the $NETWORK network)"
probe_list="backend-ready=http://backend:8080/actuator/health/readiness
backend-live=http://backend:8080/actuator/health/liveness
analytics-ready=http://analytics:8000/health/ready
mailpit=http://mailpit:8025/readyz"
running pgms-prometheus-1 && probe_list+=$'\n'"prometheus=http://prometheus:9090/-/ready"
running pgms-grafana-1 && probe_list+=$'\n'"grafana=http://grafana:3000/api/health"
results=$(docker run --rm --network "$NETWORK" -e PROBES="$probe_list" --entrypoint sh "$CURL_IMAGE" -c '
  echo "$PROBES" | while IFS="=" read -r n u; do
    printf "%s|%s|%s\n" "$n" "$u" "$(curl -s -o /dev/null -m 5 -w "%{http_code}" "$u" || echo 000)"
  done' 2>/dev/null) || results=""
[[ -n "$results" ]] || bad "could not run probes on $NETWORK (is the network up?)"
while IFS='|' read -r name url code; do
  [[ -z "$name" ]] && continue
  if [[ "$code" == 200 ]]; then ok "$name $url -> $code"
  else case $name in analytics-ready|mailpit|prometheus|grafana) warn "$name $url -> $code" ;; *) bad "$name $url -> $code" ;; esac; fi
done <<< "$results"

echo "== Data stores"
if running pgms-postgres-1; then
  docker exec pgms-postgres-1 sh -c 'pg_isready -q -U "$POSTGRES_USER" -d pgms' && ok "postgres accepting connections" || bad "postgres not ready"
  q() { docker exec -i pgms-postgres-1 sh -c 'psql -U "$POSTGRES_USER" -d pgms -X -At' <<< "$1" 2>/dev/null; }
  conns=$(q "select count(*) from pg_stat_activity where datname = 'pgms'")
  maxc=$(q "show max_connections")
  [[ -n "$conns" ]] && ok "postgres connections: $conns / $maxc"
  stuck=$(q "select count(*) from notifications where status = 'PENDING' and coalesce(next_attempt_at, created_at) < now() - interval '10 minutes'")
  failed=$(q "select count(*) from notifications where status = 'FAILED' and created_at > now() - interval '24 hours'")
  if [[ -n "$stuck" ]]; then
    [[ "$stuck" == 0 ]] && ok "notification outbox: no overdue PENDING rows" || warn "notification outbox: $stuck PENDING rows overdue > 10 min (docs/OPERATIONS.md#stuck-notifications)"
    [[ "$failed" == 0 ]] && ok "notifications FAILED in last 24h: 0" || warn "notifications FAILED in last 24h: $failed"
  fi
else bad "postgres container not running"; fi
if running pgms-redis-1; then
  pong=$(docker exec pgms-redis-1 sh -c 'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning ping' 2>/dev/null)
  [[ "$pong" == PONG ]] && ok "redis PING -> PONG" || warn "redis not answering (app fails open: rate limiting/revocation degraded)"
  mem=$(docker exec pgms-redis-1 sh -c 'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning info memory' 2>/dev/null | grep -E '^used_memory_human' | cut -d: -f2 | tr -d '\r')
  [[ -n "$mem" ]] && ok "redis used memory: $mem (maxmemory 256mb)"
else warn "redis container not running (app degrades: no rate limiting / token revocation)"; fi

if running pgms-prometheus-1; then
  echo "== Prometheus targets"
  targets=$(docker run --rm --network "$NETWORK" "$CURL_IMAGE" -s -m 5 -G http://prometheus:9090/api/v1/query \
              --data-urlencode 'query=up' 2>/dev/null \
            | grep -oE '"job":"[^"]+"[^}]*\},"value":\[[^,]+,"[01]"' \
            | sed -E 's/^"job":"([^"]+)".*,"([01])"$/\1 \2/')
  [[ -n "$targets" ]] || warn "could not read targets from prometheus"
  while read -r job value; do
    [[ -z "$job" ]] && continue
    [[ "$value" == 1 ]] && ok "target $job: up" || warn "target $job: DOWN"
  done <<< "$targets"
fi

echo
echo "Critical failures: $FAILS  Warnings: $WARNS"
[[ $FAILS -eq 0 ]]
