#!/usr/bin/env bash
# End-to-end smoke test against a running stack (docker compose up with the "demo" profile).
# Exercises the main workflows through nginx and asserts status codes:
#   login -> dashboard -> register tenant -> reserve bed -> concurrent double-booking -> check-in
#   -> idempotent payment (replay + key reuse) -> PDF receipt -> maintenance flow -> analytics
#   -> tenant portal & RBAC -> logout/revocation
# Usage: BASE_URL=http://localhost:80 DEMO_PASSWORD=... ./scripts/smoke-test.sh
set -euo pipefail

BASE_URL=${BASE_URL:-http://localhost}
PASSWORD=${DEMO_PASSWORD:-Demo12345}
PASS=0
FAIL=0
TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT

check() { # check <description> <expected> <actual>
  if [[ "$2" == "$3" ]]; then PASS=$((PASS + 1)); printf '  \033[32mPASS\033[0m %s (%s)\n' "$1" "$3"
  else FAIL=$((FAIL + 1)); printf '  \033[31mFAIL\033[0m %s (expected %s, got %s)\n' "$1" "$2" "$3"; fi
}

# call <method> <path> <token> [body] [extra curl args...] -> prints "<status> <body>"
call() {
  local method=$1 path=$2 token=$3 body=${4:-}; shift 4 || shift $#
  local out; out=$(mktemp)
  local args=(-s -o "$out" -w '%{http_code}' -X "$method" "$BASE_URL$path" -H 'Content-Type: application/json')
  [[ -n "$token" ]] && args+=(-H "Authorization: Bearer $token")
  [[ -n "$body" ]] && args+=(-d "$body")
  local status; status=$(curl "${args[@]}" "$@")
  echo "$status $(cat "$out")"
  rm -f "$out"
}
status_of() { echo "${1%% *}"; }
body_of() { echo "${1#* }"; }
login() {
  local payload response
  payload=$(jq -nc --arg e "$1" --arg p "$PASSWORD" '{email: $e, password: $p}')
  response=$(call POST /api/v1/auth/login "" "$payload")
  body_of "$response" | jq -r .accessToken
}

echo "== Authentication"
r=$(call GET /api/v1/properties ""); check "unauthenticated request rejected" 401 "$(status_of "$r")"
r=$(call POST /api/v1/auth/login "" '{"email":"owner@sunrise.example.com","password":"wrong-password1"}')
check "wrong password rejected" 401 "$(status_of "$r")"
r=$(call POST /api/v1/auth/login "" "{\"email\":\"owner@sunrise.example.com\",\"password\":\"$PASSWORD\"}")
check "owner login" 200 "$(status_of "$r")"
TOKEN=$(body_of "$r" | jq -r .accessToken)
REFRESH=$(body_of "$r" | jq -r .refreshToken)
r=$(call POST /api/v1/auth/refresh "" "{\"refreshToken\":\"$REFRESH\"}"); check "refresh token rotation" 200 "$(status_of "$r")"
r=$(call POST /api/v1/auth/refresh "" "{\"refreshToken\":\"$REFRESH\"}"); check "reused refresh token rejected" 401 "$(status_of "$r")"
TOKEN=$(login owner@sunrise.example.com)

echo "== Dashboard & catalogue"
r=$(call GET /api/v1/dashboard "$TOKEN"); check "operations dashboard" 200 "$(status_of "$r")"
echo "     occupancy: $(body_of "$r" | jq -c '.beds')"
PROPERTY=$(body_of "$(call GET '/api/v1/properties?sort=code' "$TOKEN")" | jq -r '.content[0].id')
r=$(call GET "/api/v1/beds?propertyId=$PROPERTY&status=AVAILABLE&size=5" "$TOKEN"); check "available beds search" 200 "$(status_of "$r")"
BED=$(body_of "$r" | jq -r '.content[0].id')
r=$(call GET "/api/v1/properties?sort=password" "$TOKEN"); check "non-whitelisted sort rejected" 400 "$(status_of "$r")"

echo "== Tenant onboarding & booking"
SUFFIX=$RANDOM$RANDOM
PHONE="+9198${SUFFIX:0:8}"; while [[ ${#PHONE} -lt 13 ]]; do PHONE="${PHONE}0"; done
r=$(call POST /api/v1/tenants "$TOKEN" "{\"propertyId\":$PROPERTY,\"fullName\":\"Smoke Tester\",\"email\":\"smoke$SUFFIX@example.com\",\"phone\":\"$PHONE\",\"gender\":\"MALE\",\"emergencyContacts\":[{\"name\":\"Parent\",\"relationship\":\"Father\",\"phone\":\"+919811111111\",\"primary\":true}]}")
check "create tenant" 201 "$(status_of "$r")"
TENANT=$(body_of "$r" | jq -r .id)
r=$(call POST /api/v1/tenants "$TOKEN" "{\"propertyId\":$PROPERTY,\"fullName\":\"\",\"phone\":\"12\"}")
check "validation errors" 400 "$(status_of "$r")"
PHONE2="+9197${PHONE:5}"
r=$(call POST /api/v1/tenants "$TOKEN" "{\"propertyId\":$PROPERTY,\"fullName\":\"Second Tester\",\"phone\":\"$PHONE2\"}")
check "create second tenant" 201 "$(status_of "$r")"
TENANT2=$(body_of "$r" | jq -r .id)
TODAY=$(date +%Y-%m-%d)

# fire two reservations for the same bed at the same time: exactly one may win
call POST /api/v1/bookings "$TOKEN" "{\"tenantId\":$TENANT,\"bedId\":$BED,\"expectedCheckInDate\":\"$TODAY\"}" > "$TMP_DIR/r1" &
call POST /api/v1/bookings "$TOKEN" "{\"tenantId\":$TENANT2,\"bedId\":$BED,\"expectedCheckInDate\":\"$TODAY\"}" > "$TMP_DIR/r2" &
wait
S1=$(cut -d' ' -f1 "$TMP_DIR/r1"); S2=$(cut -d' ' -f1 "$TMP_DIR/r2")
check "concurrent double-booking -> one 201 and one 409" "201,409" "$(printf '%s\n%s\n' "$S1" "$S2" | sort | paste -sd, -)"
if [[ "$S1" == 201 ]]; then WINNER=$TENANT; BOOKING=$(cut -d' ' -f2- "$TMP_DIR/r1" | jq -r .id)
else WINNER=$TENANT2; BOOKING=$(cut -d' ' -f2- "$TMP_DIR/r2" | jq -r .id); fi

r=$(call POST /api/v1/allocations/check-in "$TOKEN" "{\"tenantId\":$WINNER,\"bookingId\":$BOOKING,\"checkInDate\":\"$TODAY\"}")
check "check-in against reservation" 201 "$(status_of "$r")"
ALLOCATION=$(body_of "$r" | jq -r .allocation.id)
INVOICE=$(body_of "$r" | jq -r '.invoices[] | select(.type=="SECURITY_DEPOSIT") | .id')
DEPOSIT=$(body_of "$r" | jq -r '.invoices[] | select(.type=="SECURITY_DEPOSIT") | .totalAmount')
r=$(call POST /api/v1/allocations/check-in "$TOKEN" "{\"tenantId\":$WINNER,\"bedId\":$BED,\"checkInDate\":\"$TODAY\"}")
check "second check-in of same tenant rejected" 422 "$(status_of "$r")"

echo "== Idempotent payments"
KEY="smoke-$SUFFIX-payment"
BODY="{\"invoiceId\":$INVOICE,\"amount\":$DEPOSIT,\"method\":\"UPI\",\"externalReference\":\"UPI$SUFFIX\"}"
r=$(call POST /api/v1/payments "$TOKEN" "$BODY" -H "Idempotency-Key: $KEY"); check "payment recorded" 201 "$(status_of "$r")"
PAYMENT=$(body_of "$r" | jq -r .id)
r=$(call POST /api/v1/payments "$TOKEN" "$BODY" -H "Idempotency-Key: $KEY"); check "retry with same key replays" 200 "$(status_of "$r")"
check "replay returns the same payment" "$PAYMENT" "$(body_of "$r" | jq -r .id)"
r=$(call POST /api/v1/payments "$TOKEN" "{\"invoiceId\":$INVOICE,\"amount\":1,\"method\":\"UPI\"}" -H "Idempotency-Key: $KEY")
check "same key, different body rejected" 422 "$(status_of "$r")"
r=$(call POST /api/v1/payments "$TOKEN" "{\"invoiceId\":$INVOICE,\"amount\":1,\"method\":\"CASH\"}" -H "Idempotency-Key: smoke-$SUFFIX-over")
check "payment on settled invoice rejected" 409 "$(status_of "$r")"
CT=$(curl -s -o /dev/null -w '%{content_type}' "$BASE_URL/api/v1/payments/$PAYMENT/receipt" -H "Authorization: Bearer $TOKEN")
check "PDF receipt" "application/pdf" "$CT"

echo "== Maintenance workflow"
r=$(call POST /api/v1/maintenance "$TOKEN" "{\"propertyId\":$PROPERTY,\"category\":\"PLUMBING\",\"priority\":\"HIGH\",\"title\":\"Leaking tap\",\"description\":\"Bathroom tap leaking\"}")
check "raise maintenance request" 201 "$(status_of "$r")"
TICKET=$(body_of "$r" | jq -r .id)
STAFF=$(body_of "$(call GET "/api/v1/properties/$PROPERTY/assignments" "$TOKEN")" | jq -r '[.[] | select(.role=="STAFF")][0].userId')
r=$(call POST "/api/v1/maintenance/$TICKET/assign" "$TOKEN" "{\"assigneeUserId\":$STAFF}"); check "assign to staff" 200 "$(status_of "$r")"
r=$(call POST "/api/v1/maintenance/$TICKET/status" "$TOKEN" '{"status":"CLOSED"}'); check "invalid transition rejected" 409 "$(status_of "$r")"
r=$(call GET "/api/v1/maintenance/$TICKET/history" "$TOKEN"); check "ticket history" 200 "$(status_of "$r")"

echo "== Analytics service (Python)"
for endpoint in occupancy revenue payments tenants maintenance complaints properties; do
  r=$(call GET "/analytics/$endpoint" "$TOKEN"); check "GET /analytics/$endpoint" 200 "$(status_of "$r")"
done
echo "     occupancy: $(body_of "$(call GET /analytics/occupancy "$TOKEN")" | jq -c '.current // .snapshot // . | tostring | .[0:160]')"

echo "== Role-based access"
TENANT_TOKEN=$(login tenant.demo@example.com)
r=$(call GET /api/v1/dashboard "$TENANT_TOKEN"); check "tenant dashboard" 200 "$(status_of "$r")"
r=$(call GET /api/v1/properties "$TENANT_TOKEN"); check "tenant cannot list properties" 403 "$(status_of "$r")"
r=$(call GET /analytics/revenue "$TENANT_TOKEN"); check "tenant cannot use analytics" 403 "$(status_of "$r")"
r=$(call GET "/api/v1/tenants/$TENANT" "$TENANT_TOKEN"); check "tenant cannot read another tenant" 404 "$(status_of "$r")"
STAFF_TOKEN=$(login staff1@sunrise.example.com)
r=$(call POST /api/v1/payments "$STAFF_TOKEN" "$BODY" -H "Idempotency-Key: smoke-$SUFFIX-staff"); check "staff cannot record payments" 403 "$(status_of "$r")"

echo "== Logout revokes the access token immediately"
r=$(call POST /api/v1/auth/login "" "{\"email\":\"manager.blr@sunrise.example.com\",\"password\":\"$PASSWORD\"}")
M_TOKEN=$(body_of "$r" | jq -r .accessToken); M_REFRESH=$(body_of "$r" | jq -r .refreshToken)
r=$(call POST /api/v1/auth/logout "$M_TOKEN" "{\"refreshToken\":\"$M_REFRESH\"}"); check "logout" 204 "$(status_of "$r")"
r=$(call GET /api/v1/dashboard "$M_TOKEN"); check "revoked token rejected" 401 "$(status_of "$r")"

echo
echo "Passed: $PASS  Failed: $FAIL"
[[ $FAIL -eq 0 ]]
