# Mandi Book

Browser-based business application for a family mandi (agricultural wholesale) business. First module: **Aadat billing** in **English and Hindi**.

> **Demo build, synthetic data only.** Bill amounts follow the rules recorded in `docs/business-rules.md`; everything else that touches accounting (ledgers, payables, receivables, edit and cancel) is not built. See `docs/UNRESOLVED.md`.

## What works
- Sign-in with server-side sessions; owners and employees by invitation only; firm isolation enforced in the application and the database
- Masters: farmers, buyers, villages, items, expense heads, with English and Hindi names
- Aadat bill: village first, then farmer; lots with purchasers; expenses; server-calculated amounts per `docs/business-rules.md`; search; printable farmer bill (English or Hindi)
- Append-only audit history (database triggers refuse edits and deletes)
- Restricted runtime database role; start-up safety checks that fail closed outside the dev profiles

## Quick start
See `docs/RUNNING.md`. Stack: Java 21, Spring Boot 4.1, PostgreSQL 16, Flyway, Next.js 16, React 19, Playwright.

## Documentation
| Topic | Document |
|---|---|
| Architecture | `docs/ARCHITECTURE.md` |
| Run, test, browser tests | `docs/RUNNING.md` |
| Business rules and open decisions | `docs/business-rules.md`, `docs/UNRESOLVED.md`, `docs/ASSUMPTIONS.md` |
| Security design | `docs/AUTHENTICATION_SECURITY_DECISIONS.md`, `docs/AUTHENTICATION_AND_MULTI_TENANT_SECURITY_BLUEPRINT.md`, `docs/FIRM_ISOLATION.md` |
| Database | `docs/DB_BACKUP_AND_ROLES.md`, `docs/DB_INDEXES_AND_QUERIES.md`, `docs/DATABASE_ARCHITECTURE_ASSESSMENT.md`, `docs/ACCOUNTING_DATA_MODEL.md` |
| UI | `docs/UI_GUIDE.md`, screenshots in `docs/evidence/` |
| AI-assisted development | `CLAUDE.md`, `docs/AI_POLICY.md`, `docs/ai-log.md`, `docs/QA_EVIDENCE.md` |

## Demo
Start the stack as in `docs/RUNNING.md`, open http://localhost:3000, switch language with the selector, create a bill, save it, open it and print it. All names and firms in the demo seed are fictitious.

## Known limitations
Saved bills cannot be edited or cancelled; no ledger, payables or receivables; print layout is a draft; password reset, MFA and production email delivery are not built; Hindi wording needs a native-speaker review.
