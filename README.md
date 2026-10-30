# Mandi Book

Browser-based billing for a mandi commission agent (*aadat*), in **English and Hindi**.

**The problem.** An aadat business sells a farmer's produce, lot by lot, to several purchasers, then settles with the farmer after expenses (freight, labour, per-piece charges). Weights, rates and expense arithmetic are easy to get wrong by hand, and that costs the farmer and the firm money.

**The user.** The owner of an aadat firm and the staff who write bills at the counter.

**What it does.** Village first, then farmer; several lots per bill; the actual purchasers of each lot with their own weight and rate; expenses; amounts calculated **on the server** in one place (`BillCalculator`); a continuous bill number per firm; search, reopen and a printable farmer bill in English or Hindi. Each firm's data is isolated by the application and by the database; people join only by invitation; every change to masters and bills is written to an append-only audit history.

> **Synthetic data only.** Every name, firm and number in the demo seed, tests and screenshots is fictitious. This repository holds no real business data.

## Quick start (development profile)
Prerequisites: Docker, JDK 21, Node.js 20 or newer (tested with 24), npm.

```bash
# 1. database (PostgreSQL 16, loopback only, port 5433, synthetic data)
cp .env.example .env.local        # fill in local placeholder passwords; never commit it
set -a; source .env.local; set +a
docker compose up -d

# 2. backend, development profile with the synthetic seed (port 8081)
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=demo

# 3. frontend (port 3000)
cd frontend && npm ci && npm run build && npm start
```
People sign in only through an invitation; for a local demonstration create the first owner with `scripts/dev/provision-owner.js` (development runbook in `docs/RUNNING.md`). Without the `dev`/`demo` profile the backend is production-like and refuses to start unless every variable in `.env.example` is set safely.

## Verify
```bash
cd backend && ./mvnw -B verify                        # needs Docker (Testcontainers starts its own PostgreSQL)
cd frontend && npm test && npm run build && npx tsc --noEmit && npx eslint
# browser tests use a DISPOSABLE stack, never the development database: docs/RUNNING.md, "Browser tests"
```
Run `npm run build` before `tsc --noEmit` on a fresh checkout: Next generates the `LayoutProps` type during the build. Results of the last full run: `docs/SUBMISSION.md` and `docs/QA_EVIDENCE.md`.

## Stack
Java 21, Spring Boot 4.1, PostgreSQL 16, Flyway, JPA, Spring Security with JDBC sessions; Next.js 16, React 19, TypeScript; Playwright. Modular monolith, package by feature (`docs/ARCHITECTURE.md`).

## Documentation
| Topic | Document |
|---|---|
| **Submission summary, history boundary, checklist, demo script** | `docs/SUBMISSION.md` |
| **Provenance and prior-work disclosure** | `docs/PROVENANCE.md` |
| Architecture | `docs/ARCHITECTURE.md` |
| Run, test, browser tests, environment variables | `docs/RUNNING.md`, `.env.example` |
| Business rules and open decisions | `docs/business-rules.md`, `docs/UNRESOLVED.md`, `docs/ASSUMPTIONS.md` |
| Security design | `docs/AUTHENTICATION_SECURITY_DECISIONS.md`, `docs/AUTHENTICATION_AND_MULTI_TENANT_SECURITY_BLUEPRINT.md`, `docs/FIRM_ISOLATION.md` |
| Database | `docs/DB_BACKUP_AND_ROLES.md`, `docs/DB_INDEXES_AND_QUERIES.md`, `docs/DATABASE_ARCHITECTURE_ASSESSMENT.md`, `docs/ACCOUNTING_DATA_MODEL.md` |
| UI | `docs/UI_GUIDE.md`, screenshots in `docs/evidence/` |
| AI-assisted development | `CLAUDE.md`, `docs/AI_POLICY.md`, `docs/ai-log.md`, `docs/QA_EVIDENCE.md` |
| Third-party dependencies | `docs/THIRD_PARTY_NOTICES.md` |

## Known limitations
Saved bills cannot be edited or cancelled; no ledger, payables or receivables; the print layout is a draft and real printer output was not tested; password reset, MFA and production email delivery are not built; Hindi wording needs a native-speaker review; several accounting rules are unresolved (`docs/UNRESOLVED.md`), including the meaning of the free-text "Haste" field and whether "lot size" always means pieces (BR-110).

**Per-piece expense: confirmed rule and a known deviation.** The owner confirmed (2026-10-30) that one expense-head rate applies across all lots and that the expense is calculated and rounded per lot, then the lot amounts are summed (`docs/business-rules.md` BR-005a, BR-005b, BR-005c). The current server and browser preview add the pieces of all lots first and round once, so they give the same result only for rates with up to two decimals. For rates with three or four decimals the amount can differ by up to half a paisa per lot (two lots of one piece each at a rate of 0.005 give 0.01 now, 0.02 under the confirmed rule). This is a known implementation limitation, not fixed and not covered by a test; the calculation is **not** claimed to be fully compliant (`business-rules.md`, LIM-1).

**Secret scanner.** `scripts/dev/secret-scan.js` exits 0 on the tracked files because ten findings (eight synthetic test passwords and two false positives) are suppressed by a hash-based allow-list that the owner approved on 30 October 2026. The scanner is a safety net, not a guarantee: it reports at most one finding per line and examines only the first credential-like text of a line, so a second credential-like assignment added to an approved line would not be examined (`docs/QA_EVIDENCE.md` §1d).

**Provenance and history.** An older desktop billing program informed some concepts, and the owner is not certain of the full scope or provenance of the earlier investigation of it; provenance has not been independently established (`docs/PROVENANCE.md`). This repository is a snapshot of a working tree with only its own commits, not the complete original development history.

## Licence
The authors have not yet chosen a licence for this code; none is granted by this repository. Dependencies keep their own licences (`docs/THIRD_PARTY_NOTICES.md`).
