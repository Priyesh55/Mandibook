# Additive indexes (V5) and query-shape improvements

Phase 1 and 2 of `DATABASE_ARCHITECTURE_ASSESSMENT.md` (sections 3.1 to 3.3). Tags: **[M]** measured here, **[A]** assumption or expectation, **[D]** decision recorded.

## 1. What was added

### Migration `V5__additive_indexes.sql` (+ `V5__additive_indexes.sql.conf`, rollback `db/rollback/U5__drop_additive_indexes.sql`)
| Index | Table (columns) | Serves |
|---|---|---|
| `idx_aadat_bill_firm_date` | `aadat_bill (firm_id, bill_date DESC, id DESC)` | bill list, date filter, stable sort |
| `idx_aadat_bill_firm_farmer_date` | `aadat_bill (firm_id, farmer_id, bill_date DESC, id DESC)` | bill list filtered by farmer |
| `idx_aadat_bill_village` | `aadat_bill (village_id, firm_id) WHERE village_id IS NOT NULL` | foreign key / "bills of a village" |
| `idx_aadat_lot_item` | `aadat_lot (item_id, firm_id)` | foreign key / "lots of an item" |
| `idx_aadat_lot_buyer` | `aadat_lot (buyer_id, firm_id) WHERE buyer_id IS NOT NULL` | foreign key / "lots of a buyer" |
| `idx_aadat_expense_head` | `aadat_expense (expense_head_id, firm_id)` | foreign key / "expenses of a head" |
| `idx_{farmer,buyer,village,item,expense_head}_firm_name` | each master `(firm_id, name, id)` | list and page order `ORDER BY name, id` |

* The foreign keys are composite `(x_id, firm_id)`, so the FK indexes carry both columns.
* Nothing existing was dropped. After V5, `idx_aadat_bill_firm`, `idx_aadat_bill_date` and the `idx_<master>_firm` indexes are **drop candidates**, to be decided from production `pg_stat_user_indexes`, not now. `idx_aadat_bill_farmer (farmer_id)` stays: the composite foreign key `(farmer_id, firm_id)` needs a leading `farmer_id`.
* **Concurrent build:** `CREATE INDEX CONCURRENTLY IF NOT EXISTS`; the `.conf` file sets `executeInTransaction=false`. Flyway's log shows the migration as `[non-transactional]` [M]. The last statement fails the migration if any index is invalid (a failed concurrent build leaves an INVALID index that `IF NOT EXISTS` would otherwise accept). Recovery: drop the invalid index, run the migration again (tested).
* **Real defect found while testing [M]:** with Flyway's default transactional lock, the migration **hung forever**: Flyway's first connection stayed "idle in transaction" and the concurrent index build on its second connection waited for it. Fix: `spring.flyway.postgresql.transactional-lock=false` (a session-level advisory lock holds no transaction; still one migrator at a time). Removing it makes the build hang rather than fail (measured), so a fast guard test, `FlywayConcurrentIndexConfigTest`, checks it and the V5 `.conf` in milliseconds (section 6); the migration tests set the same option.
* **Rollback:** `U5` drops exactly these indexes and forgets V5. Run U5 before U4. Tested in disposable databases (`IndexMigrationTest`): schema identical to before V5, every row kept, V5 can be applied again; and U5 then U4 restores the V3 schema. `FirmMigrationTest`'s U4 test now starts from the V4 state (U4 undoes exactly V4); its assertions are unchanged.
* On a very large live table prefer `DROP INDEX CONCURRENTLY` by hand (it cannot run inside the implicit transaction used to run the whole file).

### Search strategy [D]
Substring search (`lower(name) LIKE '%q%'`) is **not indexed**. Reasons: the current API is a contains-search; only a trigram index (`pg_trgm`) could serve it; the measured cost at 2,000 farmers per firm is about 2 ms [M] (row 08 below); trigram behaviour on Devanagari text is untested; the search behaviour itself (prefix or substring) is still an open product decision (assessment D10). `pg_trgm` **is available** in the PostgreSQL 16 image used here (version 1.6) [M], and is a "trusted" extension, so a database owner with CREATE on the database can install it without being a superuser. If adopted later: `CREATE EXTENSION IF NOT EXISTS pg_trgm` in its own migration, GIN indexes on `lower(name)`, `lower(name_hi)`, `lower(vehicle_no)`, verified on every target environment first. Not introduced.

## 2. Measurements (synthetic data, disposable database) [M]
`scripts/db/perf-check.sh`: a disposable database `perf_check_<time>` next to the dev database (dropped afterwards; dev database untouched); schema V1 to V4; **20 firms x 5,000 bills = 100,000 bills, 300,000 lots, 200,000 expenses, 40,000 farmers**; `ANALYZE`; each query run 5 times with `EXPLAIN (ANALYZE, BUFFERS)`; median execution time; then V5 applied (concurrent builds, 0 invalid) and the same queries again. One connection, warm cache, Docker on a developer machine. **This is not a load test and not a capacity claim**; differences below about 1 ms are inside run-to-run noise.

| Query shape | Before (median ms) | After (median ms) | Plan change |
|---|---|---|---|
| 01 bill list, page 1 | 0.61 | 0.40 | backward scan of the *global* date index with a firm filter, then sort, to `idx_aadat_bill_firm_date` |
| 02 bill list, one month | 0.49 | 0.27 | same |
| 03 bill list, one farmer | 0.38 | 0.28 | bitmap on farmer index to `idx_aadat_bill_firm_farmer_date` |
| 04 bill count for paging | 3.92 | 3.90 | **unchanged** (still counts the firm's rows) |
| 05 bill text search (substring) | 19.94 | 17.01 | **unchanged** (no index applies) |
| 06 master list, whole firm, by name | 5.77 | 4.00 | sort removed; row count dominates |
| 07 master page 1 (limit 50) | 1.48 | 0.32 | index scan in name order, stops after 50 rows (no sort) |
| 08 master substring search | 1.90 | 2.05 | **unchanged** (as decided above) |
| 09 lots of an item | 2.17 | 0.39 | to `idx_aadat_lot_item` (index-only) |
| 10 lots of a buyer | 2.84 | 0.29 | to `idx_aadat_lot_buyer` (index-only) |
| 11 expenses of a head | 1.29 | 0.35 | to `idx_aadat_expense_head` (index-only) |
| 12 bills of a village | 3.14 | 0.26 | to `idx_aadat_bill_village` (index-only) |
| 13 lots of one bill | 0.52 | 0.42 | unchanged plan |

Honest reading: with 5% of the table belonging to the queried firm, the old global date index was already adequate for page 1 of the bill list. The composite indexes matter more as one firm grows relative to the table (the old path reads every other firm's recent bills and discards them) [A]; this dataset does not show that effect strongly. The clearest gains are the lookups that previously had no usable index (rows 09 to 12, 07). Count (04) and text search (05) remain the next costs.

### Live dev database
Plans were checked with `EXPLAIN` only (no `ANALYZE`) on the live dev database after V5 [M]: the bill list uses `idx_aadat_bill_firm_date` (index condition `firm_id`), lots by item use an index-only scan of `idx_aadat_lot_item`, bills by village use an index-only scan of `idx_aadat_bill_village`. The master page query still shows a bitmap scan plus sort there because that table holds only a few hundred rows (the planner's choice at that size; on the 40,000-row measurement above it used `idx_farmer_firm_name` without a sort). The dev database is too small for these plans to say anything about capacity. Index validity (all 11 valid and ready, none invalid anywhere in the database) was verified [M].

## 3. Query-shape changes (Phase 2)
| Area | Before | After | Measured |
|---|---|---|---|
| Bill detail (`GET /aadat/bills/{id}`) | lots, then each lot's item and buyer, expenses, each head, loaded one by one | two fetch-join statements: lots + item + buyer + farmer + village, then expenses + head (two list collections cannot be fetched in one statement) | **12 statements -> 2** for a bill with 3 lots and 2 expenses [M]; grows with lot count before, constant after |
| Bill list lot count | `getLots().size()` loaded every lot of every row | one grouped `count` query for the page's bill ids | 0 lot entities loaded [M]; statements: page + lot counts = 2 (page not full) or page + total + lot counts = 3 (full page) [M] |
| Master list | all rows, sorted by name | stable order `name, id`; same JSON array; bounded by `mandi.masters.max-list-size` (default 5000); header `X-Result-Truncated: true` when the cap applied | tested with a cap of 3 |
| Master paging (new) | none | `GET /api/v1/masters/{type}/page?q&activeOnly&page&size`: `{content,totalElements,page,size}`, size 1 to 200 (default 50), page 0-based, clamped, firm-scoped | tested: stable order, no overlap or gaps, ties by id, past-the-end empty, filters, clamping, isolation |

**Compatibility:** `GET /masters/{type}` keeps its JSON array shape (pick-lists in the bill form and the bills filter still use it and still receive every active record under the cap). The Masters management page now uses the paged endpoint (50 per page, previous/next, record count) and starts from a clean search on page 1 when the master type changes. Two existing browser tests that expected a brand-new master to appear in the unfiltered list now search for it first (the list is paged); their other assertions are unchanged.

**Limitations:** the cap is silent in pick-lists (the response header is never read by the UI): a firm with more than 5,000 records of one type would lose the alphabetically last ones from the bill form and the bills filter; see section 5 for every affected call site and the proposed type-ahead pick-list (not built). Page totals use an exact `count(*)` (row 04: cost grows with the firm); keyset paging or capped counts are the next step if it matters [A].

## 4. Reproduce
```bash
scripts/db/perf-check.sh                       # measurements (disposable database, dropped at the end)
cd backend && ./mvnw -B verify                 # includes IndexMigrationTest, QueryShapeIntegrationTest
cd frontend && npm run test:e2e                # includes master-paging.spec.ts
scripts/db/verify-rollback-u5.sh <after-V5 dump> <before-V5 dump>   # U5 rehearsal on a restored copy
```
Rollback in a disposable database is exercised by `IndexMigrationTest`; to rehearse by hand: apply V5, run `db/rollback/U5__drop_additive_indexes.sql`, compare `pg_indexes`.

## 5. Pick-lists and the 5,000-row cap (review, 2026-10-20; nothing changed)

The capped list is `GET /masters/{type}` (array, `name, id` order, at most `mandi.masters.max-list-size` = 5000 rows, header `X-Result-Truncated: true` when it cut the list). **The frontend never reads that header** [M: no `headers` access anywhere in `frontend/src`], so a truncated list looks complete.

| Call site | Request | Can the cap hide a needed record? |
|---|---|---|
| New bill, **Farmer** select (`BillForm`) | `/masters/farmers?activeOnly=true` | **Yes, silently.** Likely the first list to grow [A] |
| New bill, **Village** select | `/masters/villages?activeOnly=true` | Yes in theory; small lists in practice [A] |
| New bill, lot **Item** select (one per lot row) | `/masters/items?activeOnly=true` | Yes in theory; small in practice [A] |
| New bill, lot **Buyer** select (**one per lot row**) | `/masters/buyers?activeOnly=true` | **Yes, silently**, and the list is repeated in every lot row (DOM size grows with lots x buyers) |
| New bill, **Expense head** select (one per expense row) | `/masters/expense-heads?activeOnly=true` | Yes in theory; small in practice [A] |
| Bills list, **Farmer filter** (`BillSearch`) | `/masters/farmers` (**not** `activeOnly`) | **Yes, silently**, and it includes inactive farmers, so it is the longest list of all |
| Masters management page | `/masters/{type}/page` (paged) | No: every record is reachable by paging or search |
| Server side bill creation (`ref()`) | by id, firm-scoped | No cap involved, but a record the UI cannot show can still only be chosen if its id is known |

Properties of the failure [M from the code, effect sizes A]:
* The cut is by **name order**, so the records dropped are the alphabetically **last** ones; a user looking for such a farmer finds nothing and has no search box in a pick-list to reach them.
* Size of the problem today: farmers 210, buyers 2, villages 57, items 3, expense heads 2 in the dev database [M], far below 5,000. Real firms may differ; **no real count is known** [A].
* Independently of the cap, a plain `<select>` with thousands of options is slow to render and awkward to use; the buyer select multiplies that per lot row.

### Proposed type-ahead pick-list (not implemented)
1. **Component** `MasterPicker` (accessible combobox: `role="combobox"`, `aria-expanded`, `aria-controls`, `aria-activedescendant`, listbox with `role="option"`): a text input that queries the **existing** `GET /masters/{type}/page?q=<text>&activeOnly=true&size=20&page=0`. No new endpoint is needed: it is firm-scoped, ordered, bounded (size at most 200) and already tested. An empty box shows the first 20; a footer line shows "showing 20 of N, keep typing" from `totalElements`.
2. **Behaviour:** debounce about 200 ms; cancel or ignore stale responses (`AbortController`); the selected record is kept as `{id, name, nameHi}` in form state (no refetch to display it); Hindi and English labels follow the language (`nameOf`); Hindi input works because `q` is matched on both names.
3. **Keyboard:** the bill grid is keyboard-driven (Enter / Shift+Enter move between fields, `data-nav`). The picker must keep that contract: Enter on the closed picker moves on; arrow keys open and move in the list; Enter in the list selects and moves on; Escape closes without losing the field. This is the main usability risk for clerks used to fast keyboard entry [A].
4. **Where:** first Farmer in the bill form and the Bills filter (largest lists), then Buyer in the lot rows (largest DOM cost). Villages, items and expense heads can stay plain selects until a count threshold (for example more than 200 records) is reached; the same component can be switched on per field.
5. **Stop-gap that needs no new UI (small):** let `api()` return response headers and show a visible warning next to any pick-list whose response carried `X-Result-Truncated`, so a cut list is never silent. Recommended to ship before or with the picker.
6. **Tests to write with it:** component tests for the combobox contract; browser tests with more than 50 synthetic records of one type (find a record that sorts last, by English and Hindi text); the existing lot-grid keyboard tests in `ui-redesign.spec.ts` must pass unchanged; a stale-response test; a measured render comparison with 5,000 records [to be measured].
7. **Open decision (assessment D10):** whether the search should rank prefix matches first. Substring matching works today; prefix ranking would want a different query or index.

## 6. Final verification (2026-10-20) and operational caveats

### Verified in this closeout [M]
| Check | Result |
|---|---|
| Live application role | The running backend holds 10 sessions, **all as `mandi_app`**; the owner holds none after start-up. Start-up log: "Database runtime role is restricted" |
| `scripts/db/verify-runtime-role.sh` on the live dev database | all checks PASS (audit history cannot be updated, deleted, truncated or have its triggers disabled; no schema change; no `session_replication_role`; allowed write works and was rolled back) |
| V5 on the live dev database | applied (history: version 5 "additive indexes", success); logged `[non-transactional]` (and `[out of order]`, see caveats) |
| The 11 indexes | 11 valid and ready, 0 invalid anywhere in the database |
| Flyway configuration | `spring.flyway.postgresql.transactional-lock=false`; `V5__additive_indexes.sql.conf` = `executeInTransaction=false` |
| Backend suite (`mvnw verify`) | 70 of 70 passed, run as `mandi_app` |
| Browser suite (`npm run test:e2e`) against the restricted-role backend | 47 of 47 passed |
| Frontend unit tests, `tsc`, `eslint` | 28 of 28; clean; clean |
| Fresh backup `backups/mandi_demo_20261019T210914Z.dump` restored into a separate temporary database | PASS: checksum, identical fingerprint, audit triggers reject UPDATE/DELETE/TRUNCATE, cross-firm reference rejected, data present; temporary database dropped; `mandi_demo` untouched |
| U5 rollback rehearsed on a restored copy of the dev database (`scripts/db/verify-rollback-u5.sh`) | PASS: indexes, constraints and columns identical to the pre-V5 backup; no row lost; version 5 forgotten; V5 re-applied with 11 valid indexes; disposable database dropped |
| U5 and U4 rollbacks in disposable test databases | PASS (`IndexMigrationTest`, `FirmMigrationTest`): exact schema restored, rows kept, re-applicable, U5 then U4 restores V3 |

### Regression guard for the transactional-lock setting
Removing `spring.flyway.postgresql.transactional-lock=false` does **not** produce an error; it makes the application and the tests **hang** at the V5 migration. Measured: with the setting removed, the Spring-based test `QueryShapeIntegrationTest.flywayHoldsItsMigrationLockWithoutAnOpenTransaction` never ran; the build stopped only when an external timeout killed it (exit 124 after 170 s). That test therefore cannot catch removal cleanly. A fast guard was added: `FlywayConcurrentIndexConfigTest` (no Spring, no database, 3 tests, about 0.2 s of test time). Measured mutations: removing the property fails it in 15 s total build time; emptying the V5 `.conf` fails it in 20 s; both files restored afterwards. It also fails if any future migration uses `CREATE INDEX CONCURRENTLY` without a sibling `.conf` setting `executeInTransaction=false`, or if V5 loses its invalid-index check.

### Operational caveats
| Caveat | Status |
|---|---|
| **Restoring into a new cluster:** `pg_dump` custom archives carry the table privileges but **not** the roles. A fresh cluster needs the owner and `mandi_app` created first (`scripts/db/setup-runtime-role.sh`), otherwise `pg_restore` fails on the grants | [A] reasoned from how PostgreSQL works; **not tested on a fresh cluster** (the restore checks ran in the same cluster, where the roles exist) |
| `U5` uses plain `DROP INDEX`, which takes a brief exclusive lock; on a large live table drop each index with `DROP INDEX CONCURRENTLY` by hand | [A] |
| `CREATE INDEX CONCURRENTLY` needs extra disk and time proportional to table size; duration on large tables is **not measured** (the 100,000-bill dataset built the indexes in seconds) | [A] |
| A failed concurrent build leaves an INVALID index; the migration then fails loudly; recovery: drop the invalid index and run the migration again | [M] tested (`aLeftoverInvalidIndexMakesTheMigrationFailInsteadOfBeingSilentlyAccepted`) |
| The demo profile allows out-of-order migrations because older dev databases recorded the demo seed as V1000; that is why V5 logged `[out of order]` on the dev database. Production must not use `spring.flyway.ignore-migration-patterns` or out-of-order | [M] observed; production setting is a recommendation |
| A new table has no runtime access until it is added to `R__runtime_grants.sql` **and** to the matrix in `RuntimePrivilegesIntegrationTest` | [M] enforced by the test |
| Hikari keeps its minimum idle equal to its maximum, so every running instance holds `DB_POOL_MAX` sessions even when idle; with the role limit of 30 a third instance at the default pool would exhaust the role | [M] 10 sessions observed for one instance |
| Bill count for paging is an exact `count(*)` (3.9 ms for a 5,000-bill firm, unchanged by V5); substring search is unindexed (17 to 20 ms on 100,000 bills, unchanged) | [M] |
| Index benefit was measured on one synthetic dataset, one connection, warm cache; **no capacity or production-speed claim is made** | [M] method; claim limits stated |
| The dev-container owner `mandi` is a superuser and its password is in `docker-compose.yml`; `.env.local` holds the generated runtime password in clear text on the developer machine (git-ignored) | dev only |
| Backups under a OneDrive folder sync; they contain only synthetic data | [M] |
| Test runs that hang leave a PostgreSQL test container until the Testcontainers reaper removes it | [M] observed once, removed by the reaper |
