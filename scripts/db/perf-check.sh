#!/usr/bin/env bash
# Before/after measurement of the V5 indexes on SYNTHETIC data, in a DISPOSABLE database (perf_check_<time>) created
# next to the dev database in the docker-compose PostgreSQL. The dev database itself is never touched. The disposable
# database is dropped at the end. Not a load test: one connection, a few queries, 5 runs each.
#
#   scripts/db/perf-check.sh            # default size: 20 firms x 5,000 bills (100k bills, 300k lots, 200k expenses)
#   PERF_FIRMS=10 PERF_BILLS=2000 scripts/db/perf-check.sh
#
# Output: for each query shape the plan's access nodes and the median execution time (ms) before and after V5.
set -euo pipefail
cd "$(dirname "$0")/../.."
CONTAINER="${PG_CONTAINER:-mandi-postgres}"; ADMIN="${PG_ADMIN_USER:-mandi}"
FIRMS="${PERF_FIRMS:-20}"; BILLS="${PERF_BILLS:-5000}"
DB="perf_check_$(date -u +%Y%m%d%H%M%S)"
MIG=backend/src/main/resources/db/migration
adm() { docker exec -i "$CONTAINER" psql -U "$ADMIN" -d postgres -X -A -t -v ON_ERROR_STOP=1 "$@"; }
q()   { docker exec -i "$CONTAINER" psql -U "$ADMIN" -d "$DB" -X -A -t -v ON_ERROR_STOP=1 "$@"; }
trap 'adm -c "drop database if exists '"$DB"'" >/dev/null; echo "disposable database $DB dropped"' EXIT

adm -c "create database $DB" >/dev/null
for f in V1__baseline_schema V2__initial_items V3__audit_event V4__firm_ownership; do q < "$MIG/$f.sql" >/dev/null; done

echo "generating synthetic data: $FIRMS firms x $BILLS bills ..."
q >/dev/null <<SQL
insert into firm(code, name) select 'P' || g, 'Perf ' || g from generate_series(1, $FIRMS) g;
insert into farmer(name, firm_id) select 'Farmer ' || md5(f.id || '-' || g), f.id from firm f, generate_series(1, 2000) g where f.code like 'P%';
insert into buyer(name, firm_id) select 'Buyer ' || md5(f.id || '-b' || g), f.id from firm f, generate_series(1, 300) g where f.code like 'P%';
insert into village(name, firm_id) select 'Village ' || g, f.id from firm f, generate_series(1, 50) g where f.code like 'P%';
insert into item(name, firm_id) select 'Item ' || g, f.id from firm f, generate_series(1, 20) g where f.code like 'P%';
insert into expense_head(name, firm_id) select 'Head ' || g, f.id from firm f, generate_series(1, 15) g where f.code like 'P%';
create temp table rf as select id, firm_id, row_number() over (partition by firm_id order by id) rn from farmer;
create temp table rv as select id, firm_id, row_number() over (partition by firm_id order by id) rn from village;
create temp table rb as select id, firm_id, row_number() over (partition by firm_id order by id) rn from buyer;
create temp table ri as select id, firm_id, row_number() over (partition by firm_id order by id) rn from item;
create temp table rh as select id, firm_id, row_number() over (partition by firm_id order by id) rn from expense_head;
insert into aadat_bill(bill_date, farmer_id, village_id, vehicle_no, calculation_status, rule_set_id, firm_id)
select date '2025-10-20' + ((n * 37) % 365), rf.id, rv.id, 'VEH-' || (n % 997), 'NOT_CALCULATED', 'NONE', f.id
from firm f
cross join generate_series(1, $BILLS) n
join rf on rf.firm_id = f.id and rf.rn = (n * 7919) % 2000 + 1
join rv on rv.firm_id = f.id and rv.rn = (n % 50) + 1
where f.code like 'P%';
insert into aadat_lot(bill_id, line_no, item_id, pieces, weight, rate, buyer_id, firm_id)
select b.id, l, ri.id, 5, 50, 3, rb.id, b.firm_id
from aadat_bill b cross join generate_series(1, 3) l
join ri on ri.firm_id = b.firm_id and ri.rn = ((b.id + l) % 20) + 1
join rb on rb.firm_id = b.firm_id and rb.rn = ((b.id * l) % 300) + 1;
insert into aadat_expense(bill_id, seq, expense_head_id, amount, firm_id)
select b.id, s, rh.id, 10, b.firm_id
from aadat_bill b cross join generate_series(1, 2) s
join rh on rh.firm_id = b.firm_id and rh.rn = ((b.id + s) % 15) + 1;
analyze;
SQL
echo "rows: bills=$(q -c 'select count(*) from aadat_bill') lots=$(q -c 'select count(*) from aadat_lot') expenses=$(q -c 'select count(*) from aadat_expense') farmers=$(q -c 'select count(*) from farmer')"

F="(select min(id) from firm where code like 'P%')"
FARMER="(select min(id) from farmer where firm_id = $F offset 100)"
ITEM="(select min(id) from item where firm_id = $F)"
BUYER="(select min(id) from buyer where firm_id = $F)"
VILLAGE="(select min(id) from village where firm_id = $F)"
HEAD="(select min(id) from expense_head where firm_id = $F)"
declare -A SQL=(
  [01_bill_list_page1]="select b.id from aadat_bill b join farmer fm on fm.id = b.farmer_id where b.firm_id = $F and b.calculation_status <> 'DEMO_UNVERIFIED' order by b.bill_date desc, b.id desc limit 20"
  [02_bill_list_date_range]="select b.id from aadat_bill b where b.firm_id = $F and b.bill_date >= '2026-09-01' and b.bill_date <= '2026-09-30' and b.calculation_status <> 'DEMO_UNVERIFIED' order by b.bill_date desc, b.id desc limit 20"
  [03_bill_list_by_farmer]="select b.id from aadat_bill b where b.firm_id = $F and b.farmer_id = (select min(id) from farmer where firm_id = $F) and b.calculation_status <> 'DEMO_UNVERIFIED' order by b.bill_date desc, b.id desc limit 20"
  [04_bill_count_for_paging]="select count(*) from aadat_bill b where b.firm_id = $F and b.calculation_status <> 'DEMO_UNVERIFIED'"
  [05_bill_text_search_substring]="select b.id from aadat_bill b join farmer fm on fm.id = b.farmer_id where b.firm_id = $F and (lower(coalesce(b.vehicle_no,'')) like '%veh-12%' or lower(fm.name) like '%veh-12%') order by b.bill_date desc, b.id desc limit 20"
  [06_master_list_all_by_name]="select * from farmer where firm_id = $F and active order by name, id"
  [07_master_page1_limit50]="select * from farmer where firm_id = $F and active order by name, id limit 50"
  [08_master_substring_search]="select * from farmer where firm_id = $F and active and (lower(name) like '%a1b2%' or lower(coalesce(name_hi,'')) like '%a1b2%') order by name, id"
  [09_lots_by_item]="select count(*) from aadat_lot where item_id = $ITEM and firm_id = $F"
  [10_lots_by_buyer]="select count(*) from aadat_lot where buyer_id = $BUYER and firm_id = $F"
  [11_expenses_by_head]="select count(*) from aadat_expense where expense_head_id = $HEAD and firm_id = $F"
  [12_bills_by_village]="select count(*) from aadat_bill where village_id = $VILLAGE and firm_id = $F"
  [13_bill_detail_lots]="select * from aadat_lot where bill_id = (select min(id) from aadat_bill where firm_id = $F) order by line_no"
)
measure() { # $1 label -> prints "name | nodes | median_ms"
  for name in $(printf '%s\n' "${!SQL[@]}" | sort); do
    local times=() plan=""
    for run in 1 2 3 4 5; do
      plan="$(q -c "explain (analyze, buffers) ${SQL[$name]}")"
      times+=("$(echo "$plan" | grep -o 'Execution Time: [0-9.]*' | grep -o '[0-9.]*$')")
    done
    local median; median="$(printf '%s\n' "${times[@]}" | sort -n | sed -n 3p)"
    local nodes; nodes="$(echo "$plan" | grep -oE '(Index Only Scan|Index Scan|Bitmap Index Scan|Seq Scan|Parallel Seq Scan) (Backward )?(using [a-z_0-9]+ )?on [a-z_]+|Bitmap Index Scan on [a-z_0-9]+|Sort( Key: [^|]*)?' | sed 's/Sort Key.*/Sort/' | sort -u | paste -sd';' -)"
    printf '%-30s | %-9s ms | %s\n' "$name" "$median" "$nodes"
  done
}
echo; echo "=== BEFORE V5 (existing indexes only) ==="; measure before | tee "$TEMP/perf-before.txt" 2>/dev/null || measure before
echo "applying V5 (CREATE INDEX CONCURRENTLY, one statement at a time) ..."
q < "$MIG/V5__additive_indexes.sql" >/dev/null
q -c "analyze" >/dev/null
echo "invalid indexes after V5: $(q -c "select count(*) from pg_index where not indisvalid")"
echo; echo "=== AFTER V5 ==="; measure after | tee "$TEMP/perf-after.txt" 2>/dev/null || measure after
