---
name: new-migration
description: Prepare and review a new versioned Flyway migration with its rollback script, grants and isolation tests, and verify it on a disposable database. Use when a schema change is needed. Never applies anything to mandi_demo.
argument-hint: "<short description of the schema change>"
disable-model-invocation: true
allowed-tools: Read, Grep, Glob, Edit, Write, Bash(git status*), Bash(git diff*), Bash(git log*), Bash(./mvnw*)
---

# New migration

Input: `$ARGUMENTS` (what the schema change is for).

## Hard prohibitions
- **Never access `mandi_demo`**: no psql, no backend start, no Flyway against it, no `docker exec` into its container. Applying a migration to it needs the owner's explicit go-ahead and a verified backup; stop and ask.
- Never edit an applied migration (checksums). Never put a versioned migration in `db/seed`. Never run Flyway as `mandi_app`.
- No data-changing or destructive operation on any non-disposable database. Synthetic data only.

## Steps
1. Find the next version number in `backend/src/main/resources/db/migration` (highest `V<n>` plus one, `git log` to avoid clashes).
2. Write `V<n>__<name>.sql`. Every firm-owned table has `firm_id NOT NULL` and composite foreign keys `(x_id, firm_id)`. Index changes follow `docs/DB_INDEXES_AND_QUERIES.md` (`CONCURRENTLY`, sibling `.sql.conf`, invalid-index check).
3. Write `db/rollback/U<n>__...sql` that restores the previous schema.
4. Add the grant line for each new table in `R__runtime_grants.sql` and its row in `RuntimePrivilegesIntegrationTest`; record the decision in `PlatformBoundaryIntegrationTest`; never grant UPDATE, DELETE or TRUNCATE on `audit_event`.
5. Add or extend tests: a migration test on a throwaway database (data survives, constraints reject bad rows, rollback restores the schema), `FirmMigrationTest` for firm tables, an isolation case in `FirmIsolationIntegrationTest` for each endpoint. Adding a column to an existing table also changes the exact table-shape snapshots asserted by `BillWorkflowMigrationTest` and `FarmerVillageMigrationTest` (and any other test that compares a table before and after a rollback): update them deliberately, never by loosening the assertion.
6. Verify on a disposable database only: `cd backend && ./mvnw -B verify` (Testcontainers). Report the real result.
7. Update `docs/RUNNING.md` or `docs/FIRM_ISOLATION.md` if the operating procedure changes.

## Output
The files created or changed, the checks run with their real results, and the explicit statement that no persistent database was touched. List any step that was not done.
