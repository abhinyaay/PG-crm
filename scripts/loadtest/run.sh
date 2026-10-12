#!/usr/bin/env bash
# Runs scripts/loadtest/pgms-load.js in the grafana/k6 image on the pgms docker network (no local k6 needed).
#
#   ./scripts/loadtest/run.sh                      # ~45s: 35s read mix @ 6 req/s (<= 6 VUs), then 8-way bed contention
#   RATE=10 DURATION=2m ./scripts/loadtest/run.sh  # heavier (keep RATE < 20: nginx limits /api/ to 20 r/s per IP)
#   BASE_URL=http://backend:8080 ./scripts/loadtest/run.sh   # bypass nginx and its per-IP limits
#
# The password comes from APP_DEMO_PASSWORD in .env (or LOADTEST_PASSWORD in the environment) and is passed to the
# container through the environment, never on the command line. The JSON summary is written to
# run-output/loadtest/ (git-ignored).
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
K6_IMAGE=${K6_IMAGE:-grafana/k6:0.54.0}
NETWORK=${PGMS_NETWORK:-pgms_default}
OUT_DIR=$ROOT/run-output/loadtest

env_get() { { grep -E "^$1=" "$ROOT/.env" 2>/dev/null || true; } | tail -n1 | cut -d= -f2- | sed -E 's/^"(.*)"$/\1/'; }

export LOADTEST_PASSWORD=${LOADTEST_PASSWORD:-$(env_get APP_DEMO_PASSWORD)}
[[ -n "$LOADTEST_PASSWORD" ]] || { echo "error: set APP_DEMO_PASSWORD in .env (demo profile) or LOADTEST_PASSWORD" >&2; exit 1; }
docker network inspect "$NETWORK" >/dev/null 2>&1 || { echo "error: docker network $NETWORK not found - is the pgms stack up?" >&2; exit 1; }

mkdir -p "$OUT_DIR"
stamp=$(date -u +%Y%m%dT%H%M%SZ)

pass_env=(-e LOADTEST_PASSWORD)
for v in BASE_URL LOADTEST_EMAIL RATE DURATION READ_VUS CONTENTION_VUS BUSINESS_UTC_OFFSET_MINUTES; do
  [[ -n "${!v:-}" ]] && pass_env+=(-e "$v=${!v}")
done

echo "k6: $K6_IMAGE on $NETWORK -> ${BASE_URL:-http://nginx} (summary: run-output/loadtest/$stamp-summary.json)"
set +e
docker run --rm --name "pgms-k6-$stamp" --label com.pgms.role=loadtest \
  --network "$NETWORK" --cpus 1 --memory 512m \
  "${pass_env[@]}" \
  -v "$ROOT/scripts/loadtest:/scripts:ro" \
  -v "$OUT_DIR:/results" \
  "$K6_IMAGE" run --quiet --summary-export "/results/$stamp-summary.json" /scripts/pgms-load.js \
  2>&1 | tee "$OUT_DIR/$stamp-output.txt"
status=${PIPESTATUS[0]}
set -e
case $status in
  0) echo "k6: all thresholds passed" ;;
  99) echo "k6: one or more thresholds FAILED (exit 99) - see summary above" ;;
  *) echo "k6: run failed (exit $status)" ;;
esac
exit "$status"
