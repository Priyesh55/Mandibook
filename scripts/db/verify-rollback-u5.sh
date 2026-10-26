#!/usr/bin/env bash
# Rehearses the V5 rollback (db/rollback/U5__drop_additive_indexes.sql) on a RESTORED COPY of a backup, in a
# disposable database. The real dev database is never touched. Steps:
#   1. restore the backup (taken AFTER V5) into rollback_check_<time>
#   2. run U5 and compare the schema with the fingerprint of a backup taken BEFORE V5
#      (indexes, constraints and columns must be identical; every table must keep all its rows)
#   3. apply V5 again and check that all 11 indexes are valid
# Usage: scripts/db/verify-rollback-u5.sh backups/<after-V5>.dump backups/<before-V5>.dump
set -euo pipefail
cd "$(dirname "$0")/../.."
after="${1:?usage: verify-rollback-u5.sh <after-V5 dump> <before-V5 dump>}"; before="${2:?usage: verify-rollback-u5.sh <after-V5 dump> <before-V5 dump>}"
CONTAINER="${PG_CONTAINER:-mandi-postgres}"; ADMIN="${PG_ADMIN_USER:-mandi}"
tmp="rollback_check_$(date -u +%Y%m%d%H%M%S)"
q() { docker exec -i "$CONTAINER" psql -U "$ADMIN" -d "$tmp" -X -A -t -v ON_ERROR_STOP=1 "$@"; }
fail=0; check() { if [ "$2" = "$3" ]; then echo "PASS  $1"; else echo "FAIL  $1 (expected '$3', got '$2')"; fail=1; fi; }
fp() { grep "^$1" "$2" | head -1 | cut -d= -f2-; }

docker exec "$CONTAINER" psql -U "$ADMIN" -d postgres -X -c "create database $tmp" >/dev/null
trap 'docker exec "$CONTAINER" psql -U "$ADMIN" -d postgres -X -c "drop database if exists '"$tmp"'" >/dev/null; echo "disposable database $tmp dropped (original untouched)"' EXIT
docker exec -i "$CONTAINER" pg_restore -U "$ADMIN" -d "$tmp" --no-owner --exit-on-error < "$after"

check "V5 is present in the restored copy (11 valid indexes)" "$(q -c "select count(*) from pg_index i join pg_class c on c.oid = i.indexrelid where i.indisvalid and i.indisready and c.relname in ('idx_aadat_bill_firm_date','idx_aadat_bill_firm_farmer_date','idx_aadat_bill_village','idx_aadat_lot_item','idx_aadat_lot_buyer','idx_aadat_expense_head','idx_farmer_firm_name','idx_buyer_firm_name','idx_village_firm_name','idx_item_firm_name','idx_expense_head_firm_name')")" 11
q < scripts/db/fingerprint.sql > "$tmp.fp.before"
q < backend/src/main/resources/db/rollback/U5__drop_additive_indexes.sql >/dev/null

q < scripts/db/fingerprint.sql > "$tmp.fp.after"
check "indexes are exactly those of the pre-V5 backup" "$(fp indexes.md5 "$tmp.fp.after")" "$(fp indexes.md5 "$before.fingerprint.txt")"
check "constraints are exactly those of the pre-V5 backup" "$(fp constraints.md5 "$tmp.fp.after")" "$(fp constraints.md5 "$before.fingerprint.txt")"
check "columns are exactly those of the pre-V5 backup" "$(fp columns.md5 "$tmp.fp.after")" "$(fp columns.md5 "$before.fingerprint.txt")"
check "no row was lost or added by the rollback" "$(grep '^rows\.' "$tmp.fp.after" | grep -v flyway | md5sum)" "$(grep '^rows\.' "$tmp.fp.before" | grep -v flyway | md5sum)"
check "audit triggers unchanged" "$(grep '^triggers=' "$tmp.fp.after")" "$(grep '^triggers=' "$tmp.fp.before")"
check "migration 5 is forgotten (can be applied again)" "$(q -c "select count(*) from flyway_schema_history where version = '5'")" 0

# apply V5 again exactly as Flyway does: one statement at a time, outside a transaction
q < backend/src/main/resources/db/migration/V5__additive_indexes.sql >/dev/null
check "V5 re-applied: 11 valid indexes" "$(q -c "select count(*) from pg_index i join pg_class c on c.oid = i.indexrelid where i.indisvalid and i.indisready and c.relname like 'idx\_%' and c.relname in ('idx_aadat_bill_firm_date','idx_aadat_bill_firm_farmer_date','idx_aadat_bill_village','idx_aadat_lot_item','idx_aadat_lot_buyer','idx_aadat_expense_head','idx_farmer_firm_name','idx_buyer_firm_name','idx_village_firm_name','idx_item_firm_name','idx_expense_head_firm_name')")" 11
check "no invalid index anywhere" "$(q -c "select count(*) from pg_index where not indisvalid or not indisready")" 0
rm -f "$tmp.fp.before" "$tmp.fp.after"
if [ "$fail" = 0 ]; then echo "ROLLBACK U5 VERIFIED"; else echo "ROLLBACK U5 NOT VERIFIED"; exit 1; fi
