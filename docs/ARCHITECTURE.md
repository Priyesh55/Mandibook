# Architecture

Modular monolith, one backend and one browser application.

| Part | Technology | Location |
|---|---|---|
| Backend API | Java 21, Spring Boot 4.1, JPA, Flyway, Spring Security with Spring Session JDBC | `backend/` |
| Database | PostgreSQL 16 (schemas `public` for the business, `platform` for owner accounts) | migrations in `backend/src/main/resources/db/migration` |
| Web application | Next.js 16 (App Router), React 19, TypeScript, English and Hindi | `frontend/` |
| Browser tests | Playwright against a disposable stack | `frontend/e2e/`, `scripts/e2e/stack.js` |

## Backend packages (by feature)
`masters` (farmer, buyer, village, item, expense head) · `billing.aadat` (bill, lots, purchasers, expenses, `BillCalculator`, bill numbers) · `firm` (firm context, print profile) · `identity` and `security` (accounts, sessions, login throttling, firm-context filter) · `onboarding` (invitations) · `audit` (append-only history) · `shared` (errors, paging, start-up safety). Modules talk through services, never through each other's persistence.

## Request flow
Browser, then the Next.js proxy, then `/api/v1/...`: `SecurityConfig` (deny by default, CSRF) then `FirmContextFilter` (active membership, active firm, owner account state, `X-Mandi-Firm-Check`) then the controller and service, which load data only through `...AndFirmId` repository methods and call `AuditService.record` in the same transaction.

## Database roles
Flyway runs as the schema owner. The application runs as the restricted role `mandi_app` (grants in `R__runtime_grants.sql`). The platform role `mandi_platform` has no access to `public`. Every firm-owned table carries `firm_id` with composite foreign keys, so the database refuses cross-firm references. `audit_event` is append-only through triggers.

## Calculation
Bill amounts are calculated by the server in `BillCalculator` per `docs/business-rules.md`; the browser mirrors the arithmetic for preview only and the server ignores any amount the client sends for a calculated line. No ledger, payable or receivable exists.

## Configuration profiles
The baseline `application.properties` is production-like and fails closed. `dev` and `demo` add local conveniences and the synthetic seed. Details: `docs/RUNNING.md`.
