# Submission summary

Prepared 2026-10-26 and corrected on 2026-10-30. Read with `docs/PROVENANCE.md`.

> **Status of the package.** The submission ZIP built on 2026-10-26 (`MandiBook-submission.zip`) is **stale**: it equals commit `b057a7b` and lacks every change since. It has **not** been regenerated and nothing has been uploaded. The working tree is not committed. A new ZIP is to be built only after the owner decides on the open items in `docs/QA_EVIDENCE.md` §1c and the working tree is committed.

## What this repository is
A snapshot of the working Mandi Book MVP (Aadat billing, English and Hindi) in a **new Git repository**. It is not the original development history: see `docs/PROVENANCE.md`, section 1.

## Included and excluded
| Included | Excluded (and why) |
|---|---|
| Backend and frontend source, 13 versioned migrations plus rollback scripts, synthetic seed, all tests (backend, frontend unit, Playwright) | `.git` of the original (history boundary; see provenance) |
| CLAUDE.md, `.claude/` agents, skills and hooks, `.githooks`, `.github` (AI workflow controls and CI definition) | `node_modules`, `.next*`, `target`, test reports (regenerable) |
| Product, architecture, security, database, QA, AI-log and provenance documents | Local environment file, IDE state, local agent settings, database dumps and the folder that held them (never read) |
| **Current** curated screenshots `docs/evidence/submission-2026-10-30/` (15 images from the current UI, including the sign-in page) and the test summaries `docs/evidence/verification-2026-10-30/` (current tree) | Earlier UI-iteration screenshot folders (removed from this repository on 2026-10-26), a local AI-audit scratch report, the disposable-stack fixtures file |
| **Historical** evidence of commit `b057a7b`, kept unchanged: `docs/evidence/submission-2026-10-26/` (screenshots of the removed Classic layout, **not the current interface**) and `docs/evidence/verification-2026-10-26/` | |
| `.env.example` with placeholders | Investigation documents about the older program (they exist only in the original history), create-next-app template assets |

## Fresh verification of the current working tree (2026-10-30)
Version tested: commit `b057a7b` plus the uncommitted changes listed in `docs/QA_EVIDENCE.md` §1c, identified by code fingerprint `a84637de4bfd747df647f5be50c34ca3eb652dce7bd4f0b3fd696f5b1cb82f25` (294 files). Two small configuration corrections were made after those runs (the Docker container-name edit was reverted to HEAD, and the allow-list status text was reworded to record the owner's approval); the tree's fingerprint is now `891088c4ed5eff29bc7475962b65feb03b19e00fe82e842383b92bfa39f943dd`, and only the tooling tests and the scan were re-run on it (`QA_EVIDENCE.md` §1c). Backend 251 of 251 (BUILD SUCCESS); frontend unit 53 of 53; `tsc`, `eslint` and the production build exit 0; Playwright E2E **99 of 99** on a disposable stack (the committed snapshot's 105 no longer applies: 10 layout-comparison tests were removed and 4 added); guard, scanner and shutdown tests 65 of 65; secret scan exit 0 because 10 findings (8 synthetic test passwords, 2 false positives) are suppressed by an allow-list **approved by the owner on 30 October 2026**; the scanner has a known same-line limitation and is not claimed to be perfect (`QA_EVIDENCE.md` §1d). The first browser run of this tree failed (81 passed, 9 failed, 9 did not run) because older specs depended on the removed Classic desk layout; the specs were repaired and re-run, with no assertion about amounts changed (§1c). Nothing is committed. Not run: CI, physical printer, other browsers, load tests.

## Earlier verification of the committed snapshot (2026-10-26)
Commit `b057a7b`: backend 251 tests passed; frontend unit 53 of 53; eslint, build and tsc passed (tsc after build); Playwright E2E 105 of 105 on a disposable stack (final run, after removing firm-specific test rates). Details: `docs/QA_EVIDENCE.md` §1 and `docs/evidence/verification-2026-10-26/`. These counts describe that commit, not the current working tree.

Acceptance journey coverage (all by the automated E2E suite and the current curated screenshots in `docs/evidence/submission-2026-10-30/`, synthetic data): sign-in, masters and village-first farmer selection, bill with several lots and purchaser allocations, expenses, save, search and reopen, persisted values, print in English and Hindi at desktop and 390 px, validation failures. The suite includes checks against persisted rows. A manual run-through for the demo video is still to be recorded.

## Hackathon requirements checklist
The hackathon guide itself was not available to the agent that prepared this copy (it is not in either repository), so the list below follows the task brief, not the organizers' wording. Please compare it with the guide.

| Requirement | Status |
|---|---|
| Complete source code | Yes |
| README and setup instructions | Yes (`README.md`, `docs/RUNNING.md`, `.env.example`) |
| Architecture and technical decisions | Yes (`docs/ARCHITECTURE.md` and the security and database documents) |
| AI-development log | Yes (`docs/ai-log.md`, `CLAUDE.md`, `docs/AI_POLICY.md`). Tools: Claude Code for implementation; ChatGPT for planning, architecture discussions, prompt preparation and technical review, confirmed accurate by the owner on 30 October 2026 (no ChatGPT output is stored, and no application code is claimed to have been generated or committed by ChatGPT: `docs/PROVENANCE.md` §4) |
| Test and QA evidence | Yes (fresh results for the current working tree, `QA_EVIDENCE.md` §1c; CI not run; the working tree is not yet committed) |
| Provenance and prior-work disclosure | Disclosed (`docs/PROVENANCE.md`) **with provenance not independently established**: the owner is unsure of the full scope and provenance of the investigation of an older program, so no categorical statement is made either way |
| Calculation known limitation | The confirmed per-lot rounding of the per-piece expense (BR-005b) is not what the code does for some rates with 3 to 4 decimals (two lots of one piece at a rate of 0.005 give 0.01 now, 0.02 under the confirmed rule). Not fixed; the calculation is not claimed to be fully compliant (`business-rules.md`, LIM-1) |
| Git history | **Partial**: new repository with a documented boundary; the full original history was not included |
| Demo video (max 5 minutes if required) | **Not recorded**; plan in `docs/DEMO_SCRIPT.md` |
| No secrets or real business data | Scan: 10 findings, assessed by the development agent as 8 synthetic test passwords and 2 false positives, no real secret found in the tracked files; the scanner exits 0 through a hash-based allow-list of those 10, **approved by the owner on 30 October 2026** (the owner also confirmed that none of the 8 test passwords is used for any real account or system); known limitation: the scanner examines only the first credential-like text of a line, so it is a safety net, not a guarantee (`QA_EVIDENCE.md` §1d); the two per-piece rates once described as real were replaced by synthetic test values in the files of this tree, but remain in this repository's first (snapshot) commit and in the original repository (`docs/PROVENANCE.md`, section 3): owner to decide |
| Licence | Not chosen; none granted |

## Open compliance question
The guide asks for the full Git history. Whether a new repository with a disclosed boundary is acceptable is not established. Options: ask the organizers; or submit the original repository's history (it contains the older-program investigation documents, so review it first); or both, with this repository as the clean build. This repository does not claim compliance.
