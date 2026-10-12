#!/usr/bin/env bash
# Makes sure .env has what docker-compose.observability.yml needs:
#   GRAFANA_ADMIN_PASSWORD  - generated (random, 24 chars) if missing; never printed
#   PROMETHEUS_HOST_PORT / GRAFANA_HOST_PORT - defaults 58301 / 58302 (only checked here, not written)
# and that the host ports are free (unless our own pgms containers already hold them).
# Usage: ./scripts/observability-env.sh [path/to/.env]
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
ENV_FILE=${1:-$ROOT/.env}

[[ -f "$ENV_FILE" ]] || { echo "error: $ENV_FILE not found (cp .env.example .env first)" >&2; exit 1; }

env_get() { { grep -E "^$1=" "$ENV_FILE" || true; } | tail -n1 | cut -d= -f2- | sed -E 's/^"(.*)"$/\1/'; }

if [[ -z "$(env_get GRAFANA_ADMIN_PASSWORD)" ]]; then
  pw=$(LC_ALL=C openssl rand -base64 32 | tr -dc 'A-Za-z0-9' | head -c 24)
  [[ ${#pw} -eq 24 ]] || { echo "error: could not generate a password" >&2; exit 1; }
  # drop an empty placeholder line if present, then append
  if grep -qE '^GRAFANA_ADMIN_PASSWORD=$' "$ENV_FILE"; then
    tmp=$(mktemp); grep -vE '^GRAFANA_ADMIN_PASSWORD=$' "$ENV_FILE" > "$tmp"; cat "$tmp" > "$ENV_FILE"; rm -f "$tmp"
  fi
  [[ -n "$(tail -c1 "$ENV_FILE")" ]] && echo >> "$ENV_FILE"
  {
    echo "# --- Observability (docker-compose.observability.yml) ---"
    echo "GRAFANA_ADMIN_PASSWORD=$pw"
  } >> "$ENV_FILE"
  echo "Generated GRAFANA_ADMIN_PASSWORD and appended it to $ENV_FILE (user: admin). Read it there; it is not printed."
else
  echo "GRAFANA_ADMIN_PASSWORD already set in $ENV_FILE"
fi

check_port() { # check_port <port> <compose service>
  local port=$1 service=$2 holder
  if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
    holder=$(docker ps --filter "publish=$port" --format '{{.Names}}' 2>/dev/null | head -n1)
    if [[ "$holder" == "pgms-$service-1" ]]; then
      echo "port $port: already served by $holder (ok)"
    else
      echo "error: host port $port is in use by '${holder:-a non-docker process}'. Set ${3} in $ENV_FILE to a free 583xx port." >&2
      return 1
    fi
  else
    echo "port $port: free"
  fi
}

PROM_PORT=$(env_get PROMETHEUS_HOST_PORT); PROM_PORT=${PROM_PORT:-58301}
GRAF_PORT=$(env_get GRAFANA_HOST_PORT); GRAF_PORT=${GRAF_PORT:-58302}
check_port "$PROM_PORT" prometheus PROMETHEUS_HOST_PORT
check_port "$GRAF_PORT" grafana GRAFANA_HOST_PORT
