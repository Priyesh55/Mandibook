# Database backup, restore and least-privilege runtime role (local synthetic database)

Scope: the **local synthetic development database** `mandi_demo` in the docker-compose service `mandi-postgres`. Never run these scripts against any other database, and never against production or business data. Design background: `DATABASE_ARCHITECTURE_ASSESSMENT.md` (finding S2).

## 1. Setup facts (verified 2026-10-20)
| Item | Value |
|---|---|
| Server | PostgreSQL 16.15 in container `mandi-postgres`, host port 5433 |
| Database | `mandi_demo`; the container's bootstrap user `mandi` is a **superuser and owns every table** |
| Migrations | Flyway as owner; versioned V1–V4 + repeatable `R__runtime_grants.sql`; the demo profile adds `db/seed` |
| Original data | Never modified by backup/verify. Role setup adds one role; the first start with the new configuration adds one Flyway history row and grants. No table or business row changes |

## 2. Backup and restore verification
```bash
# from the repository root, with the container running (docker compose up -d)
scripts/db/backup-dev-db.sh                       # -> backups/mandi_demo_<UTC>.dump (+ .sha256, + .fingerprint.txt)
scripts/db/verify-restore.sh backups/mandi_demo_<UTC>.dump
```
* `backup-dev-db.sh` runs `pg_dump -Fc`, proves the archive is readable (`pg_restore --list`), records a SHA-256 and a **fingerprint** (row counts per table, migration history, md5 of constraints, indexes and columns, trigger list). It never overwrites a file. `backups/` and `*.dump` are git-ignored.
* `verify-restore.sh` restores into a **separate temporary database** `restore_check_<time>`, then checks: checksum; fingerprint identical to the original at backup time; the `audit_event` triggers reject UPDATE, DELETE and TRUNCATE; a cross-firm reference is rejected by the composite foreign key; synthetic data is present. It then drops **only** the temporary database. The original is never written. The exit code is non-zero if anything fails.
* Restoring over a real database is deliberately **not scripted**: create a new database, restore into it, verify, then repoint the application (`DB_URL`). Never restore over data you want to keep.
* Backups under a OneDrive folder sync; they contain only synthetic data.

Verified run (2026-10-20): 47,026-byte dump; checksum, fingerprint (7 migration rows, 11 tables, identical constraint/index/column hashes, both audit triggers enabled) and the behaviour checks passed; the temporary database was dropped; `mandi_demo` untouched.

## 3. Two connections, two roles
| | Owner / migrator | Runtime (`mandi_app`) |
|---|---|---|
| Used by | Flyway only (`spring.flyway.*`), maintenance, backups | The web application (`spring.datasource.*`) |
| Local dev | the container user `mandi` (superuser only because it is the bootstrap user) | `mandi_app`, created by `scripts/db/setup-runtime-role.sh` |
| Production | a dedicated **non-superuser** owner role | the same `mandi_app` definition |
| Create/drop/alter objects | yes (owner) | **no**: no CREATE on schema or database, owns nothing |
| Superuser / createdb / createrole / bypassrls / replication | owner only in dev | **none**; `NOINHERIT`; no role memberships; connection limit 30 |

### What `mandi_app` may do (defined in `backend/src/main/resources/db/migration/R__runtime_grants.sql`)
| Table | Privileges | Why |
|---|---|---|
| `firm` | SELECT | firm lookup |
| `farmer`, `buyer`, `village`, `item`, `expense_head` | SELECT, INSERT, UPDATE | master create/edit/activate (the API never deletes) |
| `aadat_bill`, `aadat_lot`, `aadat_expense` | SELECT, INSERT | saved bills are read-only |
| `audit_event` | **SELECT, INSERT only** | append-only: cannot UPDATE, DELETE, TRUNCATE, drop, or disable triggers (not the owner) |
| `flyway_schema_history` | none | only the migrator touches it |
| sequences, functions | none | identity columns work with INSERT on the table alone (proved by test); trigger functions need no caller privilege |

All privileges are revoked first and only the lines above are granted, so **a table added by a later migration has no runtime access until it is added to that file and to the matrix in `RuntimePrivilegesIntegrationTest`**; the test fails otherwise.

### Local setup (one time; the password never goes into version control)
```bash
export MANDI_APP_DB_PASSWORD="$(openssl rand -hex 16)"       # or any strong value
scripts/db/setup-runtime-role.sh                              # creates/updates the role (idempotent)
cp .env.example .env.local                                    # git-ignored; fill in the values (see the file)
set -a; source .env.local; set +a
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=demo   # Flyway as MIGRATION_DB_USER, app as DB_USER
```
On that start Flyway (owner connection) applies `R__runtime_grants.sql` and the role receives its privileges. Without the environment variables the application still starts the old way (owner as runtime role) and `DatabaseRoleGuard` logs a **warning**; with `MANDI_REQUIRE_RESTRICTED_DB_ROLE=true` it refuses to start. Production must set it to `true`.

PowerShell: set `$env:DB_USER`, `$env:DB_PASSWORD`, `$env:MIGRATION_DB_USER`, `$env:MIGRATION_DB_PASSWORD`, `$env:MANDI_REQUIRE_RESTRICTED_DB_ROLE`, then run `./mvnw spring-boot:run "-Dspring-boot.run.profiles=demo"`.

### Verification
* Automated: `RuntimePrivilegesIntegrationTest` (8 tests): role attributes and no memberships; exact per-table privilege matrix; audit history append-only; no schema change; no `session_replication_role`, no `CREATE EXTENSION`; identity inserts and rollback; cross-firm rejection. **Every other backend test also runs as `mandi_app`** (`TestcontainersConfiguration`); only fixtures (clean-up, extra firms) use the owner connection, which is the injected `JdbcTemplate`, not the application's own data source.
* Live check against the dev database: `scripts/db/verify-runtime-role.sh` (needs `.env.local` loaded).

### Temporary tables
`TEMPORARY` on the database was inherited from PUBLIC. The application never creates temporary tables (no temp or bulk statements in the code) and the whole backend and browser suites pass without it, so it is withdrawn from PUBLIC by `setup-runtime-role.sh` (the owner keeps it); `RuntimePrivilegesIntegrationTest` proves `mandi_app` cannot create a temp table. Reverse: `grant temporary on database <db> to public;`.

### Fail-closed for new objects
`RuntimePrivilegesIntegrationTest` also proves that a table created after the grants ran (with its identity sequence) gives `mandi_app` no privilege at all, that no sequence is accessible, and that `pg_default_acl` is empty (nothing opens future tables silently).

### Connection budget
| Item | Value |
|---|---|
| `mandi_app` limit | 30 sessions in total (`rolconnlimit`, set by the setup script; not raised) |
| Pool per application instance | `spring.datasource.hikari.maximum-pool-size=${DB_POOL_MAX:10}` (equals the Hikari default; now explicit). Hikari's minimum idle defaults to the maximum, so **one running instance holds 10 sessions** (measured: 10 `mandi_app` sessions for one backend) |
| Flyway | uses the **owner** connection (not counted against `mandi_app`): 2 sessions for a few seconds at start-up, then closed |
| Ad-hoc sessions | `psql` or `scripts/db/verify-runtime-role.sh` as `mandi_app` count against the 30 |
| Server | `max_connections` 100 (superuser-reserved 3), shared by every role, including the owner and backups |
**Rule:** `instances x DB_POOL_MAX + ad-hoc sessions <= 30`. With the defaults, 2 instances (20) leave 10; a third instance would exhaust the role. Lower `DB_POOL_MAX` or raise the limit **only with evidence** (measured pool wait times or a load test).
**Tests (measured):** with the role limited to 30 and every test context on the default pool of 10, the fourth cached Spring context failed with `FATAL: too many connections for role "mandi_app"` (24 errors). Test contexts therefore use a pool of 3 each, and the test role mirrors the 30 limit so the same failure would appear if the budget were exceeded again.

### Flyway migration lock
`spring.flyway.postgresql.transactional-lock=false` is required because V5 builds indexes concurrently (see `DB_INDEXES_AND_QUERIES.md`).

### Not covered by this phase
No RLS policies, no authentication, no per-user database roles, no TLS to the database, no password rotation procedure, no application-side `statement_timeout`. See `DATABASE_ARCHITECTURE_ASSESSMENT.md` §4.2 and §8.

## Final verification and caveats (2026-10-20 closeout)

Measured facts [M] versus assumptions [A]. Full results of the index and query work, the regression guard, and the pick-list review are in `DB_INDEXES_AND_QUERIES.md` sections 5 and 6.

| Check | Result |
|---|---|
| Sessions on the live dev database | 10, all as `mandi_app` (the owner holds none once start-up has finished) [M] |
| `scripts/db/verify-runtime-role.sh` | all PASS against the live dev database [M] |
| Backend `mvnw verify` (all as `mandi_app`, pools of 3 per test context) | 70 of 70 passed [M] |
| Browser suite against the restricted-role backend | 47 of 47 passed [M] |
| Fresh backup `mandi_demo_20261019T210914Z.dump` restored into a temporary database | PASS (checksum, fingerprint, audit triggers, cross-firm foreign key, data); temporary database dropped; original untouched [M] |
| Pre-change backup `mandi_demo_20261019T203109Z.dump` | still restores and verifies [M] |
| U5 rollback rehearsal on a restored copy (`scripts/db/verify-rollback-u5.sh <after-V5 dump> <before-V5 dump>`) | PASS: schema identical to the pre-V5 backup, no row lost, V5 re-applies with 11 valid indexes [M] |

### Scripts
* `scripts/db/backup-dev-db.sh`, `verify-restore.sh`, `setup-runtime-role.sh`, `verify-runtime-role.sh`, `perf-check.sh`, **`verify-rollback-u5.sh`** (new): all act on the local synthetic database or on disposable databases they create and drop.

### Caveats
* **Disaster recovery on a new cluster is untested** [A]: dumps contain privileges but not roles; create the owner and `mandi_app` (`setup-runtime-role.sh`) before `pg_restore`.
* Restoring over a database that holds data is deliberately not scripted.
* Passwords: the runtime password lives only in `.env.local` (git-ignored) and in the environment; there is no rotation procedure yet.
* Connection budget: `instances x DB_POOL_MAX + ad-hoc sessions <= 30` (Hikari holds its maximum even when idle) [M for one instance].
* The owner connection used by Flyway in dev is a superuser (the container's bootstrap user); production needs a dedicated non-superuser owner [A, recommendation].
* Test environment: a hung migration (see `DB_INDEXES_AND_QUERIES.md` section 6) can leave a Testcontainers PostgreSQL container until the reaper removes it [M].
