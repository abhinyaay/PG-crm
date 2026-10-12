#!/usr/bin/env bash
# Generates an RS256 key pair and prints it as single-line env vars (newlines escaped as \n),
# e.g.  ./scripts/generate-jwt-keys.sh >> .env
# In AWS the same values are stored in Secrets Manager and injected into the ECS task.
set -euo pipefail
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$tmp/private.pem" 2>/dev/null
openssl rsa -in "$tmp/private.pem" -pubout -out "$tmp/public.pem" 2>/dev/null
echo "APP_JWT_PRIVATE_KEY=\"$(awk 'BEGIN{ORS="\\n"} {print}' "$tmp/private.pem")\""
echo "APP_JWT_PUBLIC_KEY=\"$(awk 'BEGIN{ORS="\\n"} {print}' "$tmp/public.pem")\""
echo "APP_JWT_ALLOW_EPHEMERAL_KEYS=false"
