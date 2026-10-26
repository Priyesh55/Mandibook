#!/usr/bin/env bash
# Restores a backup into a SEPARATE temporary database, verifies it, then drops only that temporary database.
# The original database is never modified. Usage: scripts/db/verify-restore.sh backups/<file>.dump
set -euo pipefail
cd "$(dirname "$0")/../.."
dump="${1:?usage: verify-restore.sh backups/<file>.dump}"
CONTAINER="${PG_CONTAINER:-mandi-postgres}"; USER_="${PG_ADMIN_USER:-mandi}"
tmp="restore_check_$(date -u +%Y%m%d%H%M%S)"
psql_tmp() { docker exec -i "$CONTAINER" psql -U "$USER_" -d "$tmp" -X -A -t -v ON_ERROR_STOP=1 "$@"; }
fail=0; check() { if [ "$2" = "$3" ]; then echo "PASS  $1"; else echo "FAIL  $1 (expected '$3', got '$2')"; fail=1; fi; }

sha256sum -c "$dump.sha256" >/dev/null && echo "PASS  checksum matches"
docker exec "$CONTAINER" psql -U "$USER_" -d postgres -X -c "create database $tmp" >/dev/null
trap 'docker exec "$CONTAINER" psql -U "$USER_" -d postgres -X -c "drop database if exists '"$tmp"'" >/dev/null; echo "temporary database $tmp dropped (original untouched)"' EXIT
docker exec -i "$CONTAINER" pg_restore -U "$USER_" -d "$tmp" --no-owner --exit-on-error < "$dump"

# 1. everything in the fingerprint must equal the one recorded when the backup was taken
docker exec -i "$CONTAINER" psql -U "$USER_" -d "$tmp" -X -A -t -v ON_ERROR_STOP=1 < scripts/db/fingerprint.sql > "$tmp.fp"
if diff -u "$dump.fingerprint.txt" "$tmp.fp" > "$tmp.diff"; then echo "PASS  fingerprint identical (rows, migration history, constraints, indexes, columns, triggers)"; else echo "FAIL  fingerprint differs:"; cat "$tmp.diff"; fail=1; fi
rm -f "$tmp.fp" "$tmp.diff"

# 2. behaviour, not just structure: the restored copy must still enforce isolation and append-only history
check "audit_event UPDATE is rejected" "$(psql_tmp -c "do \$\$ begin update audit_event set details='x'; raise exception 'NOT_REJECTED'; exception when others then if sqlerrm like '%append-only%' then raise notice 'rejected'; else raise; end if; end \$\$" 2>&1 | grep -c rejected)" 1
check "audit_event DELETE is rejected" "$(psql_tmp -c "do \$\$ begin delete from audit_event; raise exception 'NOT_REJECTED'; exception when others then if sqlerrm like '%append-only%' then raise notice 'rejected'; else raise; end if; end \$\$" 2>&1 | grep -c rejected)" 1
check "audit_event TRUNCATE is rejected" "$(psql_tmp -c "do \$\$ begin truncate audit_event; raise exception 'NOT_REJECTED'; exception when others then if sqlerrm like '%append-only%' then raise notice 'rejected'; else raise; end if; end \$\$" 2>&1 | grep -c rejected)" 1
check "cross-firm reference is rejected by the composite foreign key" "$(psql_tmp -c "do \$\$ declare f2 bigint; fid bigint; begin insert into firm(code,name) values ('RESTORE_CHECK','x') returning id into f2; select id into fid from farmer limit 1; insert into aadat_bill(bill_date,farmer_id,calculation_status,rule_set_id,firm_id) values (current_date, fid, 'NOT_CALCULATED','x', f2); raise exception 'NOT_REJECTED'; exception when foreign_key_violation then raise notice 'rejected'; end \$\$" 2>&1 | grep -c rejected)" 1
check "synthetic data present (farmers > 0, bills > 0)" "$(psql_tmp -c "select (select count(*) from farmer) > 0 and (select count(*) from aadat_bill) > 0")" t
[ "$fail" = 0 ] && echo "RESTORE VERIFIED" || { echo "RESTORE NOT VERIFIED"; exit 1; }
