#!/usr/bin/env bash
# Creates (or resets the password of) the restricted runtime role `mandi_app` in the LOCAL dev database.
# Run it as the owner/superuser of the dev container. Idempotent. The password comes from the environment and is
# never written to a file by this script and never stored in source control:
#   export MANDI_APP_DB_PASSWORD="$(openssl rand -hex 16)"   # or any strong value
#   scripts/db/setup-runtime-role.sh
# Table privileges are NOT granted here: they are granted by the Flyway repeatable migration R__runtime_grants.sql
# the next time the application (or Flyway) runs with the owner/migrator connection.
set -euo pipefail
: "${MANDI_APP_DB_PASSWORD:?set MANDI_APP_DB_PASSWORD first (do not commit it)}"
CONTAINER="${PG_CONTAINER:-mandi-postgres}"; DB="${PG_DB:-mandi_demo}"; ADMIN="${PG_ADMIN_USER:-mandi}"
docker exec -i -e MANDI_APP_DB_PASSWORD "$CONTAINER" psql -U "$ADMIN" -d "$DB" -X -v ON_ERROR_STOP=1 -v db="$DB" <<'SQL'
\set pw `printf '%s' "$MANDI_APP_DB_PASSWORD"`
select format('create role mandi_app login nosuperuser nocreatedb nocreaterole noinherit nobypassrls noreplication connection limit 30 password %L', :'pw')
where not exists (select 1 from pg_roles where rolname = 'mandi_app') \gexec
select format('alter role mandi_app login nosuperuser nocreatedb nocreaterole noinherit nobypassrls noreplication connection limit 30 password %L', :'pw')
where exists (select 1 from pg_roles where rolname = 'mandi_app') \gexec
-- connect only to this database; no schema creation; the role owns nothing
select format('revoke all on database %I from mandi_app', :'db') \gexec
select format('grant connect on database %I to mandi_app', :'db') \gexec
-- TEMPORARY (creating temp tables) is inherited from PUBLIC by default. The application never uses temp tables
-- (no temp/bulk statements in the code; the whole test suite passes without it), so it is withdrawn from PUBLIC;
-- the owner keeps it. Reverse with:  grant temporary on database <db> to public;
select format('revoke temporary on database %I from public', :'db') \gexec
revoke create on schema public from mandi_app;
grant usage on schema public to mandi_app;
SQL
echo "role mandi_app ready (privileges on tables arrive with Flyway's R__runtime_grants.sql)"
