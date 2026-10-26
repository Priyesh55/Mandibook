#!/usr/bin/env bash
# Creates (or resets the password of) the PLATFORM role `mandi_platform` in the LOCAL dev database. Run it as the owner of the
# dev container. Idempotent. The password comes from the environment and is never written to a file or to source control:
#   export MANDI_PLATFORM_DB_PASSWORD="$(openssl rand -hex 16)"
#   scripts/db/setup-platform-role.sh
# The role has NO privilege on the business application (schema public). Its few privileges on schema platform are granted by
# the Flyway repeatable migration R__platform_grants.sql. Because a repeatable migration only re-runs when its file changes,
# this script forgets the applied checksum so the grants are applied at the next Flyway run (application start).
# It has no role memberships (never grant it mandi_app or any pg_* role); PlatformBoundaryIntegrationTest checks that.
set -euo pipefail
: "${MANDI_PLATFORM_DB_PASSWORD:?set MANDI_PLATFORM_DB_PASSWORD first (do not commit it)}"
CONTAINER="${PG_CONTAINER:-mandi-postgres}"; DB="${PG_DB:-mandi_demo}"; ADMIN="${PG_ADMIN_USER:-mandi}"
docker exec -i -e MANDI_PLATFORM_DB_PASSWORD "$CONTAINER" psql -U "$ADMIN" -d "$DB" -X -v ON_ERROR_STOP=1 -v db="$DB" <<'SQL'
\set pw `printf '%s' "$MANDI_PLATFORM_DB_PASSWORD"`
select format('create role mandi_platform login nosuperuser nocreatedb nocreaterole noinherit nobypassrls noreplication connection limit 10 password %L', :'pw')
where not exists (select 1 from pg_roles where rolname = 'mandi_platform') \gexec
select format('alter role mandi_platform login nosuperuser nocreatedb nocreaterole noinherit nobypassrls noreplication connection limit 10 password %L', :'pw')
where exists (select 1 from pg_roles where rolname = 'mandi_platform') \gexec
select format('revoke all on database %I from mandi_platform', :'db') \gexec
select format('grant connect on database %I to mandi_platform', :'db') \gexec
select format('revoke temporary on database %I from public', :'db') \gexec
do $$ begin
    if to_regclass('public.flyway_schema_history') is not null then
        delete from flyway_schema_history where script = 'R__platform_grants.sql';
    end if;
end $$;
SQL
echo "role mandi_platform ready (its grants arrive with Flyway's R__platform_grants.sql at the next application start)"
