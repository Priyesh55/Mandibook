# Submission summary

Prepared 2026-10-26. Read with `docs/PROVENANCE.md`.

## What this repository is
A snapshot of the working Mandi Book MVP (Aadat billing, English and Hindi) in a **new Git repository**. It is not the original development history: see `docs/PROVENANCE.md`, section 1.

## Included and excluded
| Included | Excluded (and why) |
|---|---|
| Backend and frontend source, 13 versioned migrations plus rollback scripts, synthetic seed, all tests (backend, frontend unit, Playwright) | `.git` of the original (history boundary; see provenance) |
| CLAUDE.md, `.claude/` agents, skills and hooks, `.githooks`, `.github` (AI workflow controls and CI definition) | `node_modules`, `.next*`, `target`, test reports (regenerable) |
| Product, architecture, security, database, QA, AI-log and provenance documents | Local environment file, IDE state, local agent settings, database dumps and the folder that held them (never read) |
| Curated screenshots `docs/evidence/submission-2026-10-26/` and fresh test summaries `docs/evidence/verification-2026-10-26/` | Earlier UI-iteration screenshot folders (removed from this repository on 2026-10-26; only the curated set remains), a local AI-audit scratch report, the disposable-stack fixtures file |
| `.env.example` with placeholders | Investigation documents about the older program (they exist only in the original history), create-next-app template assets |

## Fresh verification (2026-10-26, on this copy)
Backend 251 tests passed; frontend unit 53 of 53; eslint, build and tsc passed (tsc after build); Playwright E2E 105 of 105 on a disposable stack (final run, after removing firm-specific test rates). Details, exit codes and limits: `docs/QA_EVIDENCE.md`. Not run: CI, physical printer, other browsers, load tests.

Acceptance journey coverage (all by the automated E2E suite and the curated screenshots, synthetic data): sign-in, masters and village-first farmer selection, bill with several lots and purchaser allocations, expenses, save, search and reopen, persisted values, print in English and Hindi at desktop and 390 px, validation failures. The suite includes checks against persisted rows. A manual run-through for the demo video is still to be recorded.

## Hackathon requirements checklist
The hackathon guide itself was not available to the agent that prepared this copy (it is not in either repository), so the list below follows the task brief, not the organizers' wording. Please compare it with the guide.

| Requirement | Status |
|---|---|
| Complete source code | Yes |
| README and setup instructions | Yes (`README.md`, `docs/RUNNING.md`, `.env.example`) |
| Architecture and technical decisions | Yes (`docs/ARCHITECTURE.md` and the security and database documents) |
| AI-development log | Yes (`docs/ai-log.md`, `CLAUDE.md`, `docs/AI_POLICY.md`) |
| Test and QA evidence | Yes (fresh results; CI not run) |
| Provenance and prior-work disclosure | Yes (`docs/PROVENANCE.md`); the owner should confirm the scope wording |
| Git history | **Partial**: new repository with a documented boundary; the full original history was not included |
| Demo video (max 5 minutes if required) | **Not recorded**; plan in `docs/DEMO_SCRIPT.md` |
| No secrets or real business data | Scan reviewed, no secrets found; the two per-piece rates once described as real were replaced by synthetic test values in the files of this tree, but remain in this repository's first (snapshot) commit and in the original repository (`docs/PROVENANCE.md`, section 3): owner to decide |
| Licence | Not chosen; none granted |

## Open compliance question
The guide asks for the full Git history. Whether a new repository with a disclosed boundary is acceptable is not established. Options: ask the organizers; or submit the original repository's history (it contains the older-program investigation documents, so review it first); or both, with this repository as the clean build. This repository does not claim compliance.
