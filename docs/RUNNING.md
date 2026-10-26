# Running the project locally (Windows)

## Prerequisites (verified on the dev machine)
| Need | Status here | Minimum setup |
|---|---|---|
| JDK 21 | Temurin 21.0.12 was used (portable install); on the original machine system Java was 8 and was **not** changed | Set `JAVA_HOME` to a JDK 21 per shell/command |
| Maven | Use the wrapper `backend/mvnw` (Maven 3.10.0, downloaded on first run) | none |
| Node / npm | v24.4.1 / 11.4.2 | Node 20+ |
| Docker Desktop | v29.7.2; must be **running** | Start Docker Desktop (needed for dev DB and Testcontainers) |
| Browser for E2E | Installed Google Chrome (Playwright `channel: chrome`) | Or `npx playwright install chromium` |
| Free ports | **8080 is used by another program** on this machine; backend uses **8081**, frontend 3000, dev DB 5433 | Override with `SERVER_PORT`, `BACKEND_URL` |

An existing local PostgreSQL 16 is present and is **not used**.

## Start everything (synthetic data only)
```bash
docker compose up -d                       # PostgreSQL 16 on 127.0.0.1:5433 (loopback only), database mandi_demo
cd backend
export JAVA_HOME="<path to your JDK 21>"; export PATH="$JAVA_HOME/bin:$PATH"
./mvnw spring-boot:run -Dspring-boot.run.profiles=demo     # 127.0.0.1:8081, seeds synthetic data, enables opt-in demo calc
cd ../frontend && npm install && npm run build && npm run start   # 127.0.0.1:3000 (loopback only)
```
Open http://localhost:3000. Use the language selector (English / हिन्दी).
Without the `demo` profile the app starts with **no seed data** and the demonstration calculation is **disabled**.

## Configuration profiles (fail closed)
| Profile | Meaning | Database settings |
|---|---|---|
| *(none)* / `prod` / any other | **Production-like baseline.** Refuses to start (before touching a database) unless `DB_URL`, `DB_USER`, `DB_PASSWORD`, `MIGRATION_DB_USER`, `MIGRATION_DB_PASSWORD` are set, the runtime role is not `mandi`/`postgres`, neither password is the documented dev password, the two roles differ, and the restricted-role guard is on. Demo calculation, the `db/seed` location and out-of-order migrations are refused. | all explicit |
| `dev` | Local development only. Supplies the docker-compose defaults (user `mandi`, dev password) and lets a privileged role start with a warning. | defaults, overridable by env vars |
| `demo` | Implies `dev`; adds the synthetic seed, the opt-in demonstration calculation and out-of-order migrations. | as `dev` |
| `test` | Set by Maven Surefire for the automated tests (restricted role, separate migration owner; demo calculation allowed for the tests that exercise it). | set by the tests |

The recommended local start still loads `.env.local` (restricted `mandi_app` at runtime, `mandi` as Flyway owner) and the `demo` profile: `set -a; source .env.local; set +a` then `./mvnw spring-boot:run -Dspring-boot.run.profiles=demo`. A plain `./mvnw spring-boot:run` (no profile) now fails with the list of what is missing; that is intended. Rules and tests: `StartupSafetyEnvironmentPostProcessor`, `StartupSafetyTest`.

## Authentication (P1-04)
* Every `/api/**` request needs a signed-in session. Public: `GET /api/v1/auth/csrf`, `POST /api/v1/auth/login`, `POST /api/v1/auth/logout` (idempotent, CSRF-protected) and `GET /actuator/health`; everything else is refused. `GET /api/v1/auth/session` reports the remaining time without counting as activity. Flow: `GET /auth/csrf` (sets the CSRF cookie), then send its value in the `X-XSRF-TOKEN` header on every POST/PUT/PATCH/DELETE, including login.
* Sessions live in PostgreSQL (tables from migration V8): **18 hours absolute**, **30 minutes idle** (`mandi.security.session.*`). Production-like profiles require `MANDI_SECURITY_HMAC_KEY`, `MANDI_ALLOWED_ORIGINS` and refuse to start unless the session cookie is `__Host-`-prefixed, Secure, HttpOnly and SameSite lax/strict. The `dev`/`demo` profiles use an unprefixed non-Secure cookie for plain http.
* **There is no way to create a user through the application yet** (owner invitation and the platform console are later slices), and migrations V6 to V8 are not applied to the dev database until you authorize it (a backup first; Flyway applies them at the next start). Until then the running dev backend answers 401 to everything protected only after it is restarted with this build; do not restart it casually.

## Network exposure (loopback by default)
* Backend: `server.address=127.0.0.1` (property `SERVER_ADDRESS`). Frontend: `next dev/start -H 127.0.0.1` (the npm scripts). Database: `127.0.0.1:5433` in `docker-compose.yml` (variable `MANDI_DB_BIND`).
* **Deliberate non-local deployment (not the default, and not safe before authentication exists):** set `SERVER_ADDRESS=0.0.0.0` (or a specific address) **and** `MANDI_ALLOW_NON_LOOPBACK=true`; the backend refuses to start with only the first. For the frontend run `npx next start -H <address>`; for the database set `MANDI_DB_BIND=<address>` before `docker compose up -d`. Nothing in this repository changes firewall or router settings, and reachability from another machine has not been verified.
* Check what is listening: `netstat -ano | findstr "8081 5433 3000"` (Windows) and `docker ps --format "{{.Names}} {{.Ports}}"`.

## Verify
```bash
cd backend && ./mvnw -B verify                       # 22 tests, uses Testcontainers
cd frontend && npm test && npx tsc --noEmit && npx eslint && npm run build
# browser tests: disposable stack, see "Browser tests" below (never the development database)
docker exec mandi-postgres psql -U mandi -d mandi_demo -c "select * from aadat_bill;"   # independent DB check
```

## Database safety (backup/restore and the restricted runtime role)
See `DB_BACKUP_AND_ROLES.md`: `scripts/db/backup-dev-db.sh`, `scripts/db/verify-restore.sh`, `scripts/db/setup-runtime-role.sh`. The recommended local start loads `.env.local` (copy of `.env.example`, git-ignored) so the application runs as `mandi_app` while Flyway uses the owner. Under the `dev`/`demo` profile, without it the privileged default still works and a warning is logged; any other profile refuses to start.

## Notes
- The dev database credentials in `docker-compose.yml` are dev-only and non-sensitive; the application accepts them only under the `dev`/`demo` profile. Never reuse them elsewhere.
- OneDrive: this project sits under a OneDrive folder. `node_modules`, `target`, `.next` are git-ignored but will still sync; consider "Free up space"/excluding them, or moving the project outside OneDrive.
- `docker compose down -v` deletes the dev database volume (synthetic only).

## Signing in, firms and provisioning (P1-07)
* The web application now starts with a **sign-in page**. An **owner** exists only through an invitation (there is no self-registration); an **employee** exists only through an invitation from an owner of the firm.
* **Owner provisioning (development, runbook of the future platform console):** as the DATABASE OWNER of the local container run
  `node scripts/dev/provision-owner.js owner@example.test "Organisation"` (env `PG_CONTAINER`, `PG_DB`, `PG_ADMIN_USER`, `MANDI_PUBLIC_BASE_URL`). It writes only to schema `platform` (an owner account on trial and an invitation holding only the digest of a fresh token) and prints the invitation link once to your terminal. The owner opens it, chooses their own password and signs in; they then create their firm (starter items Onion, Potato, Garlic) and invite employees on the **Team** page. The tool refuses when `MANDI_ENV=production`. It needs the migrated schema: V6 to V9 must have been applied to that database.
* **Employee invitations** need email. No provider exists yet, so **without `mandi.mail.dev-sink=true` inviting answers 503 `EMAIL_NOT_CONFIGURED` and stores nothing**. The development sink (profiles `dev`/`demo`) keeps the links in memory, readable at `GET /api/v1/dev/mail`; it is refused at start-up in any other profile.
* **Not yet possible:** creating an owner without database-owner access (the platform console is not built), email delivery in production, password reset, MFA.

## Browser tests (a DISPOSABLE stack; never the development database)
```bash
node scripts/e2e/stack.js start                       # new PostgreSQL container (127.0.0.1:55434) + the real backend (127.0.0.1:8091)
cd frontend
BACKEND_URL=http://127.0.0.1:8091 npm run test:e2e    # builds .next-e2e with that proxy target, serves :3100, runs all specs
cd .. && node scripts/e2e/stack.js stop               # removes the container and the backend
```
`global-setup` creates three synthetic owners through the real invitation path (as the database owner of the **disposable** container), their firms and the demo masters the older specs need; `e2e/auth.spec.ts` covers sign-in, CSRF, expiry, firm selection, stale windows, cross-firm access, employees, suspension and provisioning. If a build fails with EPERM, a previous `next start` on port 3100 is still running: stop it and delete `.next-e2e`.

### A second, isolated browser-test stack (other ports, other container)
Use this when the standard stack (3100, 8091, 55434) is already running and must not be touched. The variables are read by `scripts/e2e/stack.js`, `playwright.config.ts`, `e2e/ports.ts`, `e2e/global-setup.ts`, `e2e/support.ts` and the backend (allowed origin):
```bash
export E2E_PG_PORT=55435 E2E_API_PORT=8092 E2E_PG_CONTAINER=mandi-e2e-pg-b E2E_WEB_PORT=3101
export E2E_API=http://127.0.0.1:8092 BACKEND_URL=http://127.0.0.1:8092
export NEXT_DIST_DIR=.next-e2e-b PLAYWRIGHT_HTML_OUTPUT_DIR=playwright-report-b E2E_FIXTURES=<a path outside the repo>/fixtures-b.json
# optional: E2E_JAR=<an already built mandi-backend jar> (when backend/target is locked by the other stack)
node scripts/e2e/stack.js start
cd frontend && npm run test:e2e -- --output=test-results-b
cd .. && node scripts/e2e/stack.js stop      # with the same E2E_API_PORT: it removes only the container and process recorded in its own state file
```
`e2e/evidence-screenshots.spec.ts` writes the bill-journey screenshots at 1440, 1280, 1024 and 390 pixels to `E2E_EVIDENCE_DIR` (default `test-results/evidence`).

## Browser tests: how the server for them is started (older note)
`npm run test:e2e` builds into `frontend/.next-e2e` and serves it on **port 3100**; it never touches `.next` or port 3000, so a server you started for manual testing keeps working. Requires the backend (demo profile) on :8081 and PostgreSQL up.

**Do not run `npm run build` while `npm run start` is serving the same folder**: the running server then returns broken pages. Stop it (Ctrl+C), build, start again.

## Database notes (verified)
- A database that has run the `demo` profile can later be started with or without it (both verified at runtime). The demo seed is repeatable and idempotent; schema migrations (V1, V2, V3, ...) apply normally.
- The `audit_event` table cannot be edited or emptied, even by SQL (`docker compose down -v` removes the whole dev database, synthetic data only).

- On Windows, `mvnw clean` fails while the backend is running (it locks files in `backend/target`); use `mvnw verify`, or stop the backend first.

## Build-output hazard with VS Code
If VS Code's Java language server is running, it can write class files into `backend/target` with the Eclipse compiler. Maven may then skip recompiling them and a test fails with "Unresolved compilation problems". Fix: delete that class from `backend/target/test-classes` (or `target/classes`) and run `mvnw verify` again. Never use `mvnw clean` while the backend is running (Windows file locks).

## Firm boundary
V4 adds the firm boundary. Rollback runbook and developer rules: `docs/FIRM_ISOLATION.md`. Take a `pg_dump` first (`docker exec mandi-postgres pg_dump -U mandi -d mandi_demo -Fc > backup.dump`, check it with `pg_restore --list`).
