# Firm Isolation: Design, Rollback Runbook and Path to Login

## 1. Target and what is implemented (commit `8421a4d`)
Target: one firm owns its data; several devices reach the same firm after login; individual users and roles follow soon; no request can reach another firm's records by changing an id.

Implemented:
- **Schema (V4):** `firm` table; `firm_id NOT NULL` on `farmer, buyer, village, item, expense_head, aadat_bill, aadat_lot, aadat_expense, audit_event`; all existing rows backfilled to firm `DEFAULT`.
- **Database-enforced isolation:** every relation carries the firm and must match it (composite foreign keys `(x_id, firm_id) → table (id, firm_id)`). A bill of one firm cannot reference a farmer, village, item, buyer or expense head of another, whatever the application does.
- **Application scoping:** `CurrentFirm` is resolved **on the server** (`RequestFirmContext`: the signed-in session's active firm, membership verified on every request; the single configured firm of earlier versions was retired in P1-05) and used by every query; ids are loaded only with `findByIdAndFirmId`. Another firm's id gives **404**, a reference to it in a request body gives **422**. A header, query parameter or body field named like a firm is ignored (tested).
- **Audit:** history rows are stored and read per firm; append-only triggers preserved (backfill used `ADD COLUMN … DEFAULT`, so no `UPDATE` and no trigger was disabled).

## 2. Authentication approach today
There is none: no login, no users. `CurrentFirm` returns the single configured firm, so the system is single-firm in practice. The **seam is the interface**: replacing the implementation is the only change needed in business code.

## 3. Smallest safe path to multi-device login and roles
1. **V5 migration:** `app_user(id, username unique, password_hash, active)` and `firm_member(firm_id, user_id, role)` (a user can later belong to several firms). Not firm-owned business data, so existing tables need no change.
2. **Spring Security** (sessions or tokens), password hashing, throttling (see `AUTHENTICATION_SECURITY_DECISIONS.md`).
3. **`CurrentFirm` from the authenticated principal** (the user's active firm); `SingleFirmContext` is retired or kept for tests.
4. **Audit actor** from the principal (adds `actor_user_id`).
5. Roles checked in the backend per endpoint (`OWNER`, `OPERATOR` proposal in the production plan).
6. Tests: the isolation suite re-run with authenticated users of two firms; a user without membership gets 403/404 everywhere.

Defence in depth for later: PostgreSQL row-level security keyed on a per-request setting, after the runtime database role is no longer a superuser (production plan item 3).

## 4. What is still missing
Firm creation and administration (done today by SQL/migration only); item seeding when a firm is created (items are per firm; the original three exist only for `DEFAULT`); a financial-year dimension (open question R12); a firm name in the UI; the login itself.

## 5. Rollback runbook (V4)
Script: `backend/src/main/resources/db/rollback/U4__remove_firm.sql` (not scanned by Flyway; run by hand).
Safeguards in the script: it **refuses to run if more than one firm exists** (removing the boundary would merge firms); it keeps every business and audit row; it removes the V4 row from `flyway_schema_history` so V4 can be applied again.

Steps:
1. Stop the backend.
2. **Back up:** `docker exec mandi-postgres pg_dump -U mandi -d mandi_demo -Fc > pre-rollback.dump`; check with `pg_restore --list`.
3. Run the script: `docker exec -i mandi-postgres psql -U mandi -d mandi_demo -v ON_ERROR_STOP=1 < U4__remove_firm.sql`.
4. Check: no `firm` table, `flyway_schema_history` has no version 4, row counts unchanged, both audit triggers present.
5. Start the **previous** application build (the current build requires V4).
6. To roll forward again: start the current build; V4 reapplies and backfills.
If anything fails: restore the dump into the database (`pg_restore --clean`).

What was tested (`FirmMigrationTest`, on a throwaway database): data created under V3 survives V4 with every row in the default firm and audit history byte-identical; after rollback the schema (columns, constraints, indexes, triggers) is **identical** to pre-V4, all rows and audit history are intact, V4 re-applies; rollback is refused with two firms.
Applied to the dev database on 2026-10-19 after a verified `pg_dump`: counts before and after were identical (155 farmers, 2 buyers, 2 villages, 3 items, 2 expense heads, 126 bills, 154 lots, 230 audit rows) and no row was outside the default firm.

## 6. Rules for developers
- Every new business table gets `firm_id NOT NULL` and composite foreign keys; add it to the V-migration **and** its rollback.
- Load by id only through a `…AndFirmId` method; never `findById` on firm-owned data.
- Never read the firm from the request. Never add an endpoint that returns rows without the firm filter.
- Add the new table to `FirmMigrationTest`, and an isolation case to `FirmIsolationIntegrationTest`.
