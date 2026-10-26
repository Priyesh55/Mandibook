#!/usr/bin/env bash
# Tries forbidden operations as the restricted runtime role against the LOCAL dev database and reports PASS/FAIL.
# Needs DB_USER and DB_PASSWORD (the runtime role) in the environment. Harmless: every forbidden statement must fail,
# and the one allowed write is rolled back.
set -uo pipefail
: "${DB_PASSWORD:?load .env.local first (set -a; source .env.local; set +a)}"
CONTAINER="${PG_CONTAINER:-mandi-postgres}"; DB="${PG_DB:-mandi_demo}"; ROLE="${DB_USER:-mandi_app}"
run() { docker exec -e PGPASSWORD="$DB_PASSWORD" "$CONTAINER" psql -h localhost -U "$ROLE" -d "$DB" -X -A -t -c "$1" 2>&1; }
fail=0
denied() {
  out="$(run "$1")"
  if echo "$out" | grep -qiE "permission denied|must be owner|must be superuser"; then echo "PASS  denied: $1"; else echo "FAIL  NOT denied: $1 -> $out"; fail=1; fi
}
if [ "$(run 'select current_user')" = "$ROLE" ]; then echo "PASS  connected as $ROLE"; else echo "FAIL  cannot connect as $ROLE"; exit 1; fi
if [ "$(run "select rolsuper or rolbypassrls or rolcreatedb or rolcreaterole from pg_roles where rolname = current_user")" = "f" ]; then
  echo "PASS  no elevated attributes"; else echo "FAIL  role has elevated attributes"; fail=1; fi
for s in "update audit_event set details='x'" "delete from audit_event" "truncate audit_event" \
         "alter table audit_event disable trigger all" "drop trigger audit_event_no_change on audit_event" \
         "create table must_not_exist(id int)" "drop table farmer" "alter table farmer add column x int" \
         "set session_replication_role = replica" "delete from farmer" "update aadat_bill set vehicle_no='x'" \
         "insert into firm(code,name) values ('X','x')" "select * from flyway_schema_history"; do
  denied "$s"
done
out="$(run "begin; insert into farmer(name, firm_id) select 'Role Probe', id from firm where code='DEFAULT'; rollback;")"
if echo "$out" | grep -qi "ROLLBACK"; then echo "PASS  allowed write works (and was rolled back)"; else echo "FAIL  allowed write failed: $out"; fail=1; fi
if [ "$fail" = 0 ]; then echo "RUNTIME ROLE VERIFIED"; else echo "RUNTIME ROLE NOT VERIFIED"; exit 1; fi
