# QA Evidence

Results below were produced by running the commands in the session named (Windows, JDK 21.0.12, Docker 29.7.2). Nothing is claimed that was not executed there. Environment: the persistent development database `mandi_demo` and port 8081 were not used.

## Session 2026-10-26, fifth pass (mobile print layout, isolated packaged build)
| Check | Command | Result |
|---|---|---|
| Mobile print regression test | `e2e/print-mobile.spec.ts` (390 and 1440 px, English and Hindi) on the isolated stack | **Failed before the fix** (390 px: 9 English cells and 5 Hindi cells wider than their cells), **passes after** (4 of 4). It also asserts that every table stays inside the sheet, that no header is broken letter by letter, and that the page does not scroll sideways |
| Full browser suite, isolated stack (3101, 8092, 55435) | `npx playwright test` | **104 passed, 0 failed, 0 skipped (7.5 min)** |
| Backend, packaged, isolated copy | `./mvnw -B verify` in `scratchpad/bk-packaged-final`, a copy of `backend/` that matched the working tree file by file (178 of 178 SHA-256, tree digest 73ceae5e0f2ea8cf, including the 32 modified and 56 untracked backend files) | **BUILD SUCCESS, exit 0, 251 tests, 0 failures, 0 errors, 0 skipped, about 7.7 min.** `spring-boot:repackage` ran: `target/mandi-backend-0.0.1-SNAPSHOT.jar`, 60,285,001 bytes, `unzip -t` reports no errors, manifest `Main-Class: org.springframework.boot.loader.launch.JarLauncher`, `Start-Class: com.mandi.MandiBackendApplication`, Spring Boot 4.1.1, 420 entries, 92 libraries, contains `BillCalculator.class` and the migrations up to V13. The jar was not started |
| Backend packaged in the repository tree | | **Still unverified:** the original stack's jar is locked. The copy build above is the only packaged build |
| Frontend | `npm test` / `npx tsc --noEmit` / `npx eslint` / `npm run build` | 52 of 52 / exit 0 / exit 0 / compiled |
| Guards, scanner, shutdown | `node --test` on the three test files | 62 of 62 |

**Mobile print defect.** Cause: the global rule `th { white-space: nowrap }` (`globals.css`) also applied to the print sheet's headers, so on a 390 px screen the fixed-layout tables could not wrap their headers and the text ran into neighbouring cells; the existing `overflow-wrap: anywhere` has no effect under `nowrap`. Fix, phone screens only (`@media screen and (max-width: 600px)`, `.bill-sheet`): auto column widths, `white-space: normal`, tighter padding and header size, numbers never split. Desktop and `@media print` rules are unchanged (checked by eye at 1440 px in Hindi after a first attempt had changed desktop wrapping and was corrected). Limits: the phone preview uses 10.5 to 11.5 px text; real printer output is untested.

## Session 2026-10-26, fourth pass (shutdown safety, evidence curation, submission readiness)
| Check | Command | Result |
|---|---|---|
| E2E shutdown safety | `node --test scripts/e2e/stack.test.js` | **26 of 26 pass.** 25 mock cases (valid, missing, malformed, wrong-type, stale, mismatched and reused-pid state; invalid container names and pids; two stacks with separate state) and 1 test with real disposable resources (two throw-away containers with random names and two dummy processes, run through the real command line): stopping one stack removed only its recorded container and process; the control container, process and state file survived; a stop without a state file touched nothing; a mismatched or wrong-port request touched nothing. The original running stack was not used |
| Guards, scanner and shutdown tests together | `node --test .claude/hooks/guard.test.js scripts/dev/secret-scan.test.js scripts/e2e/stack.test.js` | 62 of 62 pass, 0 skipped |
| Frontend | `npm test` / `npx tsc --noEmit` / `npx eslint` | 52 of 52 / exit 0 / exit 0 |
| Backend, in the repository tree | `./mvnw -B -q test-compile` | compiles. **A packaged `verify` in the repository tree is still unverified**: the jar `backend/target/mandi-backend-0.0.1-SNAPSHOT.jar` is locked by the original browser-test stack (java process started 2026-10-25 12:15), which was not stopped; the earlier in-tree run failed at `spring-boot:repackage` with "Unable to rename" after 240 passing tests. The full packaged build (251 tests, BUILD SUCCESS) succeeded only on a **copy** of the backend source |
| Evidence screenshots | `e2e/evidence-screenshots.spec.ts` on an isolated stack (3101, 8092, 55435), 4 passes | 11 images selected into `docs/evidence/submission-2026-10-26/` (see its README) after opening each one |

**The expanded sidebar in some screenshots is a capture artifact, not a product defect.** On the 1024 px print page the live sidebar measured 68 px with `data-expanded=false` and nothing focused inside it, the viewport-only capture of the same page was clean, and the stretched sidebar reappeared only in full-page captures of pages taller than the window (also at 1440 px). The pointer was not the cause (it was moved away). Why a full-page capture stretches a fixed sidebar was not investigated further.

**Found in this pass and not fixed:** the on-screen print sheet at 390 px is cramped; some table headers overlap their neighbours (not part of the curated set). The final A4 layout remains a draft (BR-107).

A full browser suite was not repeated in this pass (the product code did not change; only the new evidence spec, test comments and shutdown tooling did). The last full result stands: 100 of 100 on 2026-10-25.

## Session 2026-10-25/26, third pass (isolated browser stack and submission checks)
| Check | Command | Result |
|---|---|---|
| Browser suite, isolated stack | stack on ports 3101 (web), 8092 (API), 55435 (new container `mandi-e2e-pg-b`), see `RUNNING.md`; `npm run test:e2e -- --output=test-results-b` | **100 passed, 0 failed, 0 skipped (9.3 min)** on the final code: 95 earlier specs + 4 evidence journeys (1440, 1280, 1024, 390) + 1 new regression test |
| Backend, normal `verify` including jar packaging | `./mvnw -B verify` in a copy of the current `backend/` (the real `backend/target` jar is locked by another stack) | **BUILD SUCCESS, 251 tests, 0 failures, 0 skipped; jar repackaged.** Run on a copy, not in the repository tree |
| Frontend | `npm test` / `npx tsc --noEmit` / `npx eslint` / `npm run build` | 52 of 52 / exit 0 / exit 0 / compiled |
| Guards | `node --test .claude/hooks/guard.test.js scripts/dev/secret-scan.test.js`; `node scripts/dev/secret-scan.js` (via `/run-checks guards`) | 36 of 36; no likely secrets |
| Pre-commit hook | `.githooks/pre-commit` on a temporary copy of the index holding a synthetic secret assembled at run time | exit 1 with `file:line` only; exit 0 without it; the real index was unchanged; nothing committed |
| `/review`, `/new-migration` | run on `BillCalculator.java`; a dry-run migration V14 in a disposable copy | review produced 4 findings (1 fixed); V14 applied on a Testcontainers database and 2 shape-snapshot migration tests failed as designed, which led to a note in the skill. Nothing was added to the repository |

Defects found and handled in this pass:
| # | Finding | Handling |
|---|---|---|
| 1 | An empty new bill at 390 px was 177 px wider than the window (the "lots without a value" chip could not wrap); the existing 390 px test only measured after data was entered | CSS fix (`.tb-status > .chip`), new test `empty-bill-mobile.spec.ts` |
| 2 | Keyboard picker test raced the filtered list (it could press keys against the previous, unfiltered options) | the test now waits for the filtered option; 3 of 3 repeat runs pass. Not caused by the port changes |
| 3 | The navigation Hindi-title test timed out once while the machine was loaded by other work | passed when run alone and in the final full run; treated as load-related flakiness, cause not otherwise established |
| 4 | Print screenshots at 1024 px show the navigation sidebar expanded over the page | probably the pointer resting on the rail during capture; not confirmed |

Screenshots of this pass (synthetic data, 44 files, `<size>-<step>.png` for sizes 1440, 1280, 1024, 390 and steps 01 to 11) are in the session scratchpad `evidence-final/`, not yet copied into `docs/evidence/`.

## Session 2026-10-25, second pass (calculator review and finalization)
| Check | Command | Result |
|---|---|---|
| Backend tests | `cd backend && ./mvnw -B verify -Dspring-boot.repackage.skip=true` | **BUILD SUCCESS, 250 tests, 0 failures, 0 errors, 0 skipped** (240 earlier + 9 new `BillCalculatorTest` + 1 new API test). The repackage step was skipped on purpose because another process holds the jar (see below); no test was skipped |
| Frontend | `npm test` / `npx tsc --noEmit` / `npx eslint` / `npm run build` | 52 of 52 pass / exit 0 / exit 0 / compiled |
| Guards | `node --test .claude/hooks/guard.test.js scripts/dev/secret-scan.test.js`; `node scripts/dev/secret-scan.js` | 36 of 36 pass; no likely secrets |
| Browser tests | not run | the disposable stack from `scripts/e2e/stack.js` is already running (container `mandi-e2e-pg`, backend pid 13760 on 8091, `next start` pid 28448 on 3100), started at 12:15 and 13:21 today, not by this task. The specs hard-code port 3100 and `stack.js start` would stop that stack first, so a second stack cannot run beside it. Stop it with `node scripts/e2e/stack.js stop` when its owner agrees, then follow `RUNNING.md` |

Calculator changes in this pass (no formula changed): negative weight, rate, pieces or commission base are rejected; a missing per-piece rate or missing gross or expenses is rejected instead of failing with a null error; a calculated amount above 999,999,999,999 (the limit of the `numeric(18,6)` columns) is refused with the validation message `validation.amount.tooLarge` (English and Hindi) before anything is saved. The new API test saves oversized lot and expense amounts and expects 422 with no bill number consumed. It was not run before the guard existed, so it is not shown to have failed first.

## Session 2026-10-25 (repository hygiene and AI governance)
| Check | Command | Result |
|---|---|---|
| Backend tests | `cd backend && ./mvnw -B verify` (JDK 21, Testcontainers) | **240 tests, 0 failures, 0 errors, 0 skipped.** The build then **failed at the packaging step** (`spring-boot:repackage`: "Unable to rename" the jar), because a disposable browser-test stack left running from an earlier session holds `backend/target/mandi-backend-0.0.1-SNAPSHOT.jar`. This is a file lock, not a test failure; it was not retried because the stack belongs to another run and was not stopped |
| Frontend unit tests | `cd frontend && npm test` | 52 / 52 pass |
| Type check | `npx tsc --noEmit` | exit 0, no errors |
| Lint | `npx eslint` | exit 0, no findings |
| Production build | `npm run build` | compiled; routes listed |
| Guard hook and secret scanner tests | `node --test .claude/hooks/guard.test.js scripts/dev/secret-scan.test.js` | 36 / 36 pass (34 hook cases, 2 scanner cases) |
| Secret scan of tracked files | `node scripts/dev/secret-scan.js` | no likely secrets found |
| CI workflow structure | parsed with `js-yaml`; `.claude/settings.json` parsed as JSON | both valid; the workflow itself was **not executed** (needs a Git host) |
| Browser tests (Playwright) | not run | the disposable stack on ports 8091, 55434 and 3100 was already running from an earlier session and was left alone; run `node scripts/e2e/stack.js stop`, then the commands in `RUNNING.md`, to repeat |
| Hook active in a live session | observed | the PreToolUse hook blocked one agent command that contained a prohibited phrase |

Not configured or verified: sandboxing and network egress limits (not verified on this Windows host); Gitleaks (not installed; the dependency-free scanner above is used instead); branch protection and required checks (no Git remote).

## Earlier results (recorded in earlier sessions, not re-run today)
These were recorded when the work was done and describe the code of that time; the counts have since grown (see today's results).
- Original build (2026-10-17): 28 backend tests, 6 unit tests and 6 browser tests passed.
- Redesign (2026-10-19): 28 backend, 11 unit; browser 15 of 16 with one test-side failure later diagnosed and fixed.
- Audit history (2026-10-19): 35 backend, 18 unit, 27 browser; a mutation check (removing `@Transactional`) made the atomicity test fail as intended.
- Firm boundary (2026-10-19): 43 backend, 27 browser; a mutation check (ignoring the firm in a lookup) made the isolation test fail as intended; migration rollback verified.
- Navigation (2026-10-20): 28 unit, 43 browser.

## Failures and fixes (honest record)
| # | Failure | Root cause | Fix |
|---|---|---|---|
| 1 | Maven could not resolve the parent POM | generated version `4.1.1.RELEASE` does not exist | corrected to `4.1.1` |
| 2 | `pieces: 2.5` accepted and truncated to 2 | Jackson float to int coercion | `accept-float-as-int=false`, test added |
| 3 | Backend would not start on an existing database | seed was versioned; Flyway ignore patterns incomplete | repeatable idempotent seed; both `*:missing` and `*:future` patterns in the dev profile |
| 4 | A test class failed with "Unresolved compilation problems" | the IDE's Java compiler overwrote a class file | delete the generated class and rebuild; documented in `RUNNING.md` |
| 5 | One browser test failed intermittently once | unknown | not reproduced in 40 probe attempts and 6 further full runs; **still unexplained** |
| 6 | Backend packaging failed on 2026-10-25 | a running disposable stack holds the jar | not fixed; stop the stack first |

## Not verified
Real printer output; other browsers and screen readers; load with many bills; Hindi wording by a native speaker; accounting rules beyond `business-rules.md`.

## Screenshots
`docs/evidence/` holds screenshots of the web application with synthetic data. Folders `ui-layouts-2026-10-25` show a temporary layout switcher used for design experiments and are not the final interface; some full-page captures show a sticky header overlapping the page, and `ui-desk-1920-2026-10-25/print-*-a4.png` show the navigation sidebar covering the print. They are kept as development evidence and should be regenerated before a final submission.

## Fresh verification of the submission copy (2026-10-26)
Run on the submission repository copy (not the original working tree), Windows, JDK 21.0.12, Node 24.4.1, Docker. Raw summaries: `docs/evidence/verification-2026-10-26/`. Earlier numbers in this file are historical.

| Check | Result |
|---|---|
| Backend `./mvnw -B verify` (Testcontainers, own throwaway PostgreSQL containers) | PASS: 251 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS, exit 0 |
| Packaged jar | `mandi-backend-0.0.1-SNAPSHOT.jar` built; `unzip -t` reports no errors; contains `BillCalculator`, the 13 versioned migrations and the synthetic seed |
| Frontend `npm test` | PASS: 52 of 52, exit 0 |
| `npx eslint` | exit 0 |
| `npm run build` | exit 0 |
| `npx tsc --noEmit` | exit 2 on a fresh tree (`LayoutProps` is generated by the build); exit 0 after `npm run build` |
| Playwright E2E (Chrome) on a second, disposable stack (own container, ports 55445 / 8095 / 3105, own fixtures file) | PASS: 104 of 104, exit 0, 6.7 minutes |
| Secret scan (`scripts/dev/secret-scan.js`) | 10 hits, all reviewed: test-only password constants of disposable databases, a diagram label and a `$DB_PASSWORD` reference; no real secret found |

Not run: CI (the workflow was only inspected; it needs a Git host), physical printer output, other browsers, load tests. The E2E run covers print views in English and Hindi at desktop and 390 px in a browser, not on paper.
