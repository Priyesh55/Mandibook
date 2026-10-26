# CLAUDE.md — Mandi Book (repository: Mandi Business App)

Browser-based business application for a family mandi (agricultural wholesale) business. First module: **Aadat billing** (English + Hindi). Built with AI assistance throughout; the MVP is the foundation of the long-term product, not a prototype.

@frontend/AGENTS.md

## Hard rules (never break)
1. **Synthetic data only; no real business data.** Never read, attach, query, copy or upload database files, dumps, `backups/` or `.env*`. Use the synthetic seed (`backend/src/main/resources/db/seed`) and disposable databases. Never commit database files, executables or credentials. Do not paste proprietary or third-party source or documentation into the repository (`docs/AI_POLICY.md`).
2. **Never invent business rules.** Formulas, units, rounding, expense order, bill numbering, meaning of the final total, receivables/payables and accounting postings are **unverified**. See `docs/business-rules.md` and `docs/UNRESOLVED.md`. If a rule is not in `docs/business-rules.md`, do not implement it — implement the surrounding feature and keep the rule isolated.
3. **Bill amounts are calculated by the server per `docs/business-rules.md`, in ONE place: `BillCalculator`** (the owner explicitly confirmed on 2026-10-25 only: 5% commission, rate per 100 kg, per-piece expenses; rounding details are recorded but not separately confirmed, and nothing else is approved) (`backend/.../billing/aadat`): the RATE IS ENTERED PER 100 KG (30 a kg is 3000); lot value = weight (kg) x rate / 100 to the rupee (one rounding); purchaser value the same; commission 5% fixed, charged to the buyer (one decimal, never deducted from the farmer); an expense head with a predefined rate per piece = pieces x rate (two decimals), every other expense typed; net = gross - expenses to the rupee; rounding half-up. The browser mirrors the arithmetic live (`lib/billDraft.ts`, `lib/decimal.ts`), the server stores its own result (status `CALCULATED`, rule set `OWNER_2026_10_25_Q100`) and ignores any amount the client sends for a calculated line. Bills saved before that keep `NOT_CALCULATED` and their typed amounts. `AadatCalculationService` keeps the opt-in `DEMO_UNVERIFIED` mode (server flag `mandi.calculation.demo-enabled`); the bill form no longer offers it. Unconfirmed: the tie rule (half-up is the default), a second per-piece rate for heavy pieces, ledger postings.
4. **Buyer-per-lot is an assumption (A1)**, not a decision. Do not build features that depend on it being final.
5. **Do not build deferred features**: ledgers/vouchers, cash book, trial balance, GST/e-invoicing, data import, payments, receivables/payables. **Authentication, the OWNER/EMPLOYEE business roles and the separate platform-administrator authority are NOT deferred**: they are approved and in scope (`docs/AUTHENTICATION_AND_MULTI_TENANT_SECURITY_BLUEPRINT.md`, `docs/AUTHENTICATION_SECURITY_DECISIONS.md`).
6. Never claim a test or browser check passed unless it was actually executed in this session.
7. **Security architecture (approved, mandatory).** The **Platform Admin manages owner accounts only**: no access to firms, employees, bills, business activity or credentials, no impersonation, separate authority (own role, schema, session). The business application runs as the **restricted runtime role** `mandi_app`, never the migration-owner role (`mandi`) that Flyway uses. **Authentication and server-side authorization are mandatory before the business API is reachable beyond this machine**; client-side hiding is not security. **Development shortcuts must never become production defaults**: the baseline configuration is production-like and fails closed; database defaults, the synthetic seed, the demonstration calculation and a privileged runtime role exist only under the explicit `dev`/`demo` profiles. No production authentication bypass, demo identity or default credential.

## Stack and commands
- Backend: Java 21, Spring Boot 4.1.1, PostgreSQL 16, Flyway, JPA. Port **8081** (8080 is used by another program on the dev machine).
- Frontend: Next.js 16 (App Router), React 19, TypeScript. **Read `frontend/node_modules/next/dist/docs/` before using Next APIs** — this is not the Next.js of older training data.
- JDK 21 is required (on the original development machine system Java was 8 and a portable JDK 21 was used). Set `JAVA_HOME` per command; never change system Java.

```
# database (synthetic data only)
docker compose up -d

# backend tests (Testcontainers needs Docker running)
cd backend && JAVA_HOME=<jdk21> ./mvnw -B verify
# run backend with synthetic seed + opt-in demo calculation (`demo` implies the development-only `dev` profile;
# a start WITHOUT dev/demo is production-like and refuses to start unless DB_* and MIGRATION_DB_* are set, see below)
cd backend && JAVA_HOME=<jdk21> ./mvnw spring-boot:run -Dspring-boot.run.profiles=demo

# frontend
cd frontend && npm test && npx tsc --noEmit && npx eslint && npm run build
# browser tests need the DISPOSABLE stack (never the dev database): node scripts/e2e/stack.js start; then in frontend:
# BACKEND_URL=http://127.0.0.1:8091 npm run test:e2e ; finally node scripts/e2e/stack.js stop  (docs/RUNNING.md)
# never run `npm run build` while a `next start` is serving .next (it breaks that server)
```
More: `docs/RUNNING.md`.

## UI
White-and-blue theme from CSS tokens in `frontend/src/app/globals.css` (see `docs/UI_GUIDE.md`); `src/lib/theme.test.ts` enforces WCAG AA contrast. Status colours only for status. Lot/expense grids are keyboard-driven (Enter / Shift+Enter). The printed bill is a **draft** layout (`docs/business-rules.md` BR-107).

## Database and audit rules
- Schema changes are versioned Flyway migrations in `db/migration` only. **Never put a versioned migration in `db/seed`**: the demo seed is the repeatable, idempotent `R__synthetic_demo_data.sql` (a versioned seed once blocked later schema migrations). Demo profile allows out-of-order; other profiles do not.
- `spring.flyway.ignore-migration-patterns` is set in `application-dev.properties` only, and must keep **both** `*:missing` and `*:future` there (setting only one silently drops Flyway's default and broke startup once). Production keeps Flyway's own checks.
- Every state change of masters and bills must call `AuditService.record` in the **same transaction**. `audit_event` is append-only (database triggers); never add an update/delete path. Saved bills stay read-only until the edit/cancel rule is decided (BR-105).

## Configuration profiles and start-up safety
- `application.properties` is the **production-like baseline**: no database defaults (a missing `DB_URL`/`DB_USER`/`DB_PASSWORD`/`MIGRATION_DB_USER`/`MIGRATION_DB_PASSWORD` fails), `mandi.db.require-restricted-role=true`, demo calculation off, no seed location, `server.address=127.0.0.1`. `application-dev.properties` (profile `dev`, implied by `demo`) holds the local conveniences. Surefire runs tests in profile `test`.
- `StartupSafetyEnvironmentPostProcessor` refuses to start, before any database use, outside `dev`: owner/admin runtime role (`mandi`, `postgres`), documented dev password, identical runtime and Flyway role, restricted-role guard off, demo calculation, `db/seed` location or out-of-order migrations (the last three allowed only in `test`). In **every** profile a non-loopback `server.address` needs the second switch `MANDI_ALLOW_NON_LOOPBACK=true`. Rules and tests: `StartupSafetyTest`; operation: `docs/RUNNING.md`.
- The frontend scripts bind `127.0.0.1`; `docker-compose.yml` publishes the database on `127.0.0.1` (override `MANDI_DB_BIND` deliberately).

## Authentication (P1-04)
- Spring Security + Spring Session JDBC (`com.mandi.security`): deny by default, the public endpoints are the explicit list in `SecurityConfig`; every new `/api/**` route is protected automatically and `AuthenticationFlowIntegrationTest` enumerates all routes and fails if one answers an anonymous caller. Login throttling and bounded hashing are in `AuthService`; the 18-hour limit in `SessionPolicyFilter`; never add an ordinary endpoint that reports session time (it would extend the idle timer): `PassiveSessionStatusFilter` exists for that. Security events (`auth_event`) never contain passwords, tokens, session ids or a stranger's email. Tests with MockMvc get a synthetic principal from `TestcontainersConfiguration` (test code only); sign-in behaviour is tested over real HTTP.
- **Firm context (P1-05):** `CurrentFirm` is `RequestFirmContext`: the firm comes only from the signed-in session and `FirmContextFilter` verifies, on EVERY `/api/**` request outside `/api/v1/auth/`, an ACTIVE membership, an active firm and the owner's account state (`public.account_access`; no account = blocked). Answers: 409 `FIRM_SELECTION_REQUIRED` (several firms, none chosen; one firm is chosen automatically), 409 `FIRM_CHECK_REQUIRED`/`FIRM_MISMATCH` (the `X-Mandi-Firm-Check` header must name the session's active firm, so a stale tab cannot write elsewhere), 403 `NO_MEMBERSHIP`/`FIRM_INACTIVE`/`ACCOUNT_BLOCKED`, 401. There is no ambient firm: outside a verified request `CurrentFirm.id()` throws. A new business route needs no check of its own; `FirmContextIsolationIntegrationTest` enumerates all routes. Remaining: no per-role permission policy yet (OWNER and EMPLOYEE have the same rights), repositories still extend `JpaRepository`, audit actor is still `anonymous` (P1-09), and there is no way to provision users yet.

## Sign-in, firms and provisioning (P1-07)
- Frontend: `SessionProvider`/`AuthGate` (src/lib/session.tsx, components/AuthGate.tsx) mirror the SERVER session in memory only; no password, id or token is stored by the page. `lib/api.ts` adds the CSRF token (cookie, retried once after `CSRF_REJECTED`) and `X-Mandi-Firm-Check` (the firm of the ready state) and tells the session layer about 401 / stale firm / no access. The page under an expired session stays mounted (inert) beneath a sign-in dialog; the app is re-created when the firm changes.
- People enter ONLY by invitation: `OnboardingService` (employee invitation by an OWNER of the active firm; owner invitation created in schema platform by the DB owner or the future console, accepted through the SECURITY DEFINER functions of V9; firm creation only for a person with an allowing owner account). No endpoint sets, shows or resets a password or token. Invitation links need the mail port: without a provider (`mandi.mail.dev-sink` false) the API answers 503 and stores nothing; the sink is dev/test only and refused elsewhere at start-up.
- `scripts/dev/provision-owner.js` is the development runbook (DB-owner credential, writes only to `platform`, refuses in production). `scripts/e2e/stack.js` builds the disposable browser-test stack.

## Platform database boundary
- The platform role `mandi_platform` (`scripts/db/setup-platform-role.sh`) has NO privilege in schema `public` and only the allow-list in `R__platform_grants.sql` in schema `platform`. V7 withdrew PostgreSQL's default `USAGE` on `public` and `EXECUTE` on functions from `PUBLIC`: every role that needs `public` must be granted it explicitly. **Any new table, view or function must be a conscious decision in `PlatformBoundaryIntegrationTest`** (catalog-generated checks fail otherwise); every `SECURITY DEFINER` function needs a pinned `search_path` and a security review. Never grant the platform role a role membership.

## Runtime database role
- The application must run as the restricted role `mandi_app`, not the owner/superuser; Flyway uses the owner connection (`MIGRATION_DB_*`). Grants live in `db/migration/R__runtime_grants.sql`: **any new table needs its grant line there and in the matrix of `RuntimePrivilegesIntegrationTest`**, otherwise the app has no access to it. Never grant UPDATE/DELETE/TRUNCATE on `audit_event`, never run Flyway as `mandi_app`. All backend tests run as `mandi_app`; fixtures use the owner `JdbcTemplate`. Backup/restore and role setup: `docs/DB_BACKUP_AND_ROLES.md`.

## Indexes, paging and connections
- Index migrations use `CREATE INDEX CONCURRENTLY IF NOT EXISTS` with a sibling `<migration>.sql.conf` (`executeInTransaction=false`), keep `spring.flyway.postgresql.transactional-lock=false` (otherwise Flyway hangs), end with an invalid-index check, and ship a `db/rollback/U<n>` script tested in `IndexMigrationTest`. Details and measurements: `docs/DB_INDEXES_AND_QUERIES.md`.
- Master pick-lists use `GET /masters/{type}` (array, capped, `X-Result-Truncated`); browsing uses `/masters/{type}/page`. Bill detail loads in 2 statements (`QueryShapeIntegrationTest` guards the counts).
- Connection budget: `instances x DB_POOL_MAX + ad-hoc <= 30` (role limit); see `docs/DB_BACKUP_AND_ROLES.md`.

## Firm ownership rules
- Every firm-owned table has `firm_id NOT NULL` and **composite foreign keys** `(x_id, firm_id)`; add them in the migration **and** in `db/rollback`, and extend `FirmMigrationTest`.
- Load firm-owned data by id only through a `...AndFirmId` method; list queries must filter by `CurrentFirm`. Never read the firm from the request (header, parameter or body). Add an isolation case to `FirmIsolationIntegrationTest` for each new endpoint.
- Take a `pg_dump` before running any migration against the dev database. Rollback runbook: `docs/FIRM_ISOLATION.md`.
- Only the rules in `docs/business-rules.md` exist: do not add amount, ledger or numbering logic beyond them until the owner approves a worked example.
- The VS Code Java extension (Eclipse compiler) can overwrite files in `backend/target`. If a test class fails with "Unresolved compilation problems", delete that class from `target/test-classes` and rebuild.

## Architecture
Modular monolith, package-by-feature: `masters` (farmer, buyer, village, item, expense head), `billing.aadat` (bill, lots, expenses, `calc`), `shared` (errors, paging). Modules must not reach into each other's persistence; talk through services. REST under `/api/v1`, localized errors via `Accept-Language: en|hi`. UI strings live in `frontend/src/lib/dictionary.ts` (both languages required; a unit test enforces key parity).

## AI workflow controls
- `.claude/settings.json`: permission rules (deny force-push, hard resets, `git clean`, `--no-verify`, destructive Docker and `rm -rf`, reads of `.env*` and `backups/`; ask for push, commit, Docker, migrations and agent config) and the PreToolUse hook `.claude/hooks/guard.js` (tests: `node --test .claude/hooks/guard.test.js`). Never edit these to make a task easier.
- Skills: `/review` (read-only), `/run-checks` (real results only), `/new-migration` (disposable database only; never `mandi_demo`). Policy: `docs/AI_POLICY.md`.
- Secrets: `node scripts/dev/secret-scan.js` (file:line only); opt-in pre-commit `git config core.hooksPath .githooks`. CI: `.github/workflows/ci.yml`.
- Local-only AI files (`CLAUDE.local.md`, `.claude/settings.local.json`) are git-ignored.

## Working method
- Plan → tests first for rules → implement in small commits → run the full check ladder → independent review → fix.
- **Check ladder (all must pass):** backend `verify`; frontend `npm test`, `tsc --noEmit`, `eslint`, `npm run build`; Playwright E2E; independent DB check of persisted rows.
- **Bounded repair:** at most 3 attempts per failing check, then stop and report the blocker with hypotheses.
- Record AI contributions and failures honestly in `docs/ai-log.md`; keep evidence in `docs/evidence/` and `docs/QA_EVIDENCE.md`.
- Commit messages end with the co-author trailer. Prefer new commits over amends.

## Where things are
`docs/ARCHITECTURE.md` · `docs/business-rules.md` · `docs/ASSUMPTIONS.md` · `docs/UNRESOLVED.md` · `docs/RUNNING.md` · `docs/AI_POLICY.md` · `docs/QA_EVIDENCE.md` · `docs/ai-log.md`

## Bill saves, audit actors and pickers (V10)
- `POST /api/v1/aadat/bills` accepts `Idempotency-Key` (8-64 chars `[A-Za-z0-9_-]`): same key + same content replays the saved bill (200, `Idempotent-Replay: true`), different content is 409. The bill form keeps one key per form.
- Audit events record `actor_user_id`, `actor_membership_id`, `actor_role`, `request_id` from the server-side request context only; events from before V10 stay anonymous (no backfill). Migrations V10 must not be applied to `mandi_demo` without the owner's go-ahead.
- Farmer, village and buyer selection use `MasterPicker` (paged search); in Playwright use `pickMaster()` from `e2e/support.ts`.

## Bill workflow (V11): numbers, purchasers, entered totals
- **V11 must not be applied to `mandi_demo`** without the owner go-ahead and a verified backup (see `/new-migration`). Demonstrate on a fresh Flyway-built database or the disposable stack. Run `scripts/e2e/stack.js` with `JAVA_HOME` set to JDK 21 (the ambient JAVA_HOME is Java 8).
- Bill numbers: one continuous series per firm across financial years, assigned **at save** inside the save transaction from `bill_number_counter` (row locked with FOR UPDATE through the JPA EntityManager, never a separate JdbcTemplate). Owner-only `PUT /api/v1/firm/bill-numbering` sets the first number before the first bill. Financial year is 1 April to 31 March (`fy_start_year` is a generated column).
- Purchaser rows (`aadat_lot_buyer`) hold what each purchaser actually bought (weight and rate entered, value and commission calculated); nothing is split automatically; a difference from the lot is only a warning. The lot `buyer_id` is the single buyer of older bills only.
- **Motor Bhada.** The bhada paid in the bill details is an expense of the head named Motor Bhada, added to the bill automatically (no button); a firm without that head cannot save a bill with bhada, and the message says to add it.
- **Farmer bill and buyer side.** The saved bill, its print and the bill list show the FARMER side only (gross, expenses, net); the financial year is not shown on a bill (its date is). Purchasers and the 5% commission charged to them are entered on the bill and shown in a folded "buyer side" block of the view, never on the print. Farmer side and buyer side are never added together; print and view show the server figures and calculate nothing.
- Firm print profile (`address`, `phone`, `licence_no`, `shop_no`): column-level UPDATE grant for the runtime role, owner-only endpoint `/api/v1/firm/profile`.
- Frontend: one draft state in `components/BillForm.tsx` feeds the four sections in `components/bill/`; indigo tokens in `globals.css` (`--ind-*`); browser helpers in `e2e/support.ts` (`fillLot`, `addPurchaser`, `addExpense`, `chooseLot`).

## Village-first billing and master dialogs (V12)
- A farmer belongs to a village (`farmer.village_id`, nullable for older farmers). The bill picks the village first; the farmer list is `GET /masters/farmers/page?villageId=`; changing the village clears only the farmer. The server refuses a bill whose farmer belongs to a different village than the bill's. V12 must not be applied to `mandi_demo` without the owner go-ahead and a verified backup.
- Masters are added on the bill in dialogs (`MasterDialog`, village, farmer, purchaser) that reuse the masters-page form (`MasterForm`) and API. Dialogs are rendered OUTSIDE the bill `<form>` (a nested form would submit the bill). Cancel, Escape and failures leave the draft untouched.
- Design tokens are a blue-indigo ramp (`--ind-*`); layout max width 1440 px. Screenshot evidence lives in `docs/evidence/ui-2026-10-25/`.
