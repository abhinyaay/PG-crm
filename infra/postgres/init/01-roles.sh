#!/bin/sh
# Runs once, on first initialisation of the PostgreSQL data volume (docker-entrypoint-initdb.d).
# Creates least-privilege roles:
#   pgms_app      - owns the application schema, runs Flyway migrations, NOT a superuser
#   analytics_ro  - read-only access to the analytics.* views only (granted by migration V2)
# Secrets are passed as psql variables (quoted by psql), never interpolated into SQL text.
set -eu

psql -v ON_ERROR_STOP=1 \
     -v app_pw="$APP_DB_PASSWORD" -v ro_pw="$ANALYTICS_DB_PASSWORD" -v db="$POSTGRES_DB" \
     --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
CREATE ROLE pgms_app LOGIN PASSWORD :'app_pw';
CREATE ROLE analytics_ro LOGIN PASSWORD :'ro_pw';
ALTER ROLE analytics_ro SET default_transaction_read_only = on;

REVOKE ALL ON DATABASE :"db" FROM PUBLIC;
GRANT CONNECT, CREATE ON DATABASE :"db" TO pgms_app;
GRANT CONNECT ON DATABASE :"db" TO analytics_ro;
ALTER SCHEMA public OWNER TO pgms_app;

-- extensions need elevated rights; create them here so migrations (IF NOT EXISTS) are no-ops
CREATE EXTENSION IF NOT EXISTS btree_gist;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- analytics_ro must not read base tables, only the views published in schema "analytics"
REVOKE ALL ON SCHEMA public FROM PUBLIC;
SQL
