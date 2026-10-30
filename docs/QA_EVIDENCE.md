# QA Evidence

**How to read this file.** Section 1c holds the results for the **current working tree** (2026-10-30), identified by commit and code fingerprint; use these for the submitted state. Section 1 holds results of runs made on THIS submission repository on 2026-10-26 for the **committed snapshot `b057a7b`** (before and after the removal of firm-specific test rates; see `docs/PROVENANCE.md` §3); they are not results for the current tree (the browser suite differs: 105 tests then, 99 now). Section 2 is **historical**: results from the original development repository and its working tree, kept for the record of what failed and was fixed. Historical counts (240, 250, 100, 22 tests and so on) describe earlier code and are not claims about this repository. Nothing is claimed that was not executed in the session named.

## 1c. Verification of the current working tree (2026-10-30)

### Exact version tested
- **Commit:** `b057a7b41c0c0d53aa36aa3e08a78db01757226d` (branch `main`, 8 commits, all 2026-10-26). **The working tree is not committed**: it holds the changes below on top of that commit, so the version tested is "`b057a7b` plus uncommitted changes", identified by a content fingerprint.
- **Uncommitted changes at the time of the run** (`git status`): the five alternative bill layouts, the layout switcher and the "Layouts" menu group were removed (Lot cards is the only bill layout); the sign-in page was redesigned (`LoginForm.tsx`, new `LoginArt.tsx`) and no longer shows breadcrumbs; `e2e/bill-layouts.spec.ts` became `e2e/bill-entry.spec.ts` (10 tests, 6 of them one per layout, replaced by 4); test helpers and three spec lines were adapted to the lot-cards layout (see below); the secret-scan allow-list and its tests were added (§1d); `CLAUDE.md` was trimmed and `frontend/CLAUDE.md` added; the dev container name `mandi-postgres` had been changed to `mandibook-postgres` in `docker-compose.yml` and `scripts/dev/provision-owner.js` only. **That rename was reverted after the runs (2026-10-30): both files now equal their `b057a7b` versions and use the original name `mandi-postgres`, as the rest of the documents and scripts do** (see "Corrections after the runs" below). `backend/` has no modified or untracked file.
- **Code fingerprint:** SHA-256 over every tracked or untracked (not ignored) file under `backend/`, `frontend/`, `scripts/`, `.claude/`, `.github/`, `.githooks/` and `docker-compose.yml`, excluding `CLAUDE.md` files, line endings normalised: `files=294 sha256=a84637de4bfd747df647f5be50c34ca3eb652dce7bd4f0b3fd696f5b1cb82f25`. It was identical at the start and at the end of the final browser run, and every result in the table below was produced on this fingerprint. Documents (`docs/`, `README.md`) and screenshots are outside the fingerprint.
- **Corrections after the runs (2026-10-30), and the fingerprint now.** Two configuration corrections were made after the verified runs: (1) `docker-compose.yml` and `scripts/dev/provision-owner.js` were restored to their committed `b057a7b` versions (the container name); (2) the top-level `description` of `scripts/dev/secret-scan-allowlist.json` was reworded twice, first to "pending owner review" and then, after the owner's approval of 30 October 2026, to "OWNER-APPROVED" with the known same-line limitation (no entry, hash or scanner rule changed either time). The fingerprint of the tree now is `files=294 sha256=891088c4ed5eff29bc7475962b65feb03b19e00fe82e842383b92bfa39f943dd` (it was `1268db40…` between the two rewordings). **The backend, frontend and browser results were not re-run on it.** They are not claimed for it beyond this reasoning: none of those suites reads `docker-compose.yml`, the browser tests pass the database container name to `provision-owner.js` explicitly, and no application source or test changed. The guard, scanner and shutdown tests (65 of 65, exit 0) and the secret scan (exit 0, 10 suppressed) **were** re-run on the new fingerprint, and the 10 allow-list hashes still equal the 10 live finding hashes. The current-UI screenshots (`docs/evidence/submission-2026-10-30/`) come from the verified run (a84637de…), plus two sign-in captures of the same already-built frontend.
- **Environment:** Windows 11, JDK 21.0.7, Node 24.4.1, Docker. Raw summaries: `docs/evidence/verification-2026-10-30/`.

### Results
| Check | Result |
|---|---|
| Backend `./mvnw -B verify` (Testcontainers) | PASS: 251 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS, 7 min 18 s, jar packaged. Run in a copy made with `git archive HEAD backend` (backend tree object `4dbc5ddbace3708ef9ab9c7d985739a3aa54ca08`) because the running stacks hold `backend/target`; the working tree has no backend changes, so the copy is the current backend |
| Frontend `npm test` | PASS: 53 of 53 |
| `npx tsc --noEmit` / `npx eslint` | exit 0 / exit 0 |
| `npm run build` | exit 0 (built into `.next-e2e-b`, so the `.next` served by the developer's running server was not touched) |
| Playwright E2E (Chrome) | **PASS: 99 of 99, exit 0, 7.1 min**, on a disposable stack (own container on 55456, backend 8098, web 3108). **Not 105**: the 10 layout-comparison tests were removed and 4 bill-entry tests added |
| `node --test` guard hook, secret scanner, stack shutdown | PASS: 65 of 65 (34 guard, 5 scanner of which 3 are new allow-list tests, 26 shutdown) |
| Secret scan | exit 0: "no likely secrets found (10 verified test constant(s) suppressed by scripts/dev/secret-scan-allowlist.json)" (the word "verified" is the scanner's fixed output text; the ten suppressions were **approved by the owner on 30 October 2026**, §1d); without the allow-list it is exit 1 with 10 findings (§1d) |
| Known calculation limitation (not a test result) | The per-piece expense is rounded once on the bill's total pieces; the owner-confirmed rule rounds per lot, then sums. Identical up to two decimals; for 3 to 4 decimal rates it can differ (two lots of one piece at 0.005: 0.01 now, 0.02 confirmed). No test covers it, and none was added. `docs/business-rules.md`, LIM-1 |

### Failures found and repaired during this verification (honest record)
The first browser run of the current tree **failed**: 81 passed, 9 failed, 9 did not run (serial specs stop after a failure). Cause: the older specs were written against the "Classic desk" lot grid, which was the default until the lot-cards layout became the only layout. They looked for ids and structure that only the Classic desk had: `section-lots` (a width and position check in three specs), a single `lot-detail` (the lot cards show one per lot, so `chooseLot` and the evidence spec hit a strict-mode violation) and a tinted grid cell for the current lot. These were selector and structure assumptions, not calculation or persistence defects. Repairs (bounded repair, attempt 1 and 2): `data-testid="section-lots"` on the lot-cards container; `chooseLot` checks the chosen card's `aria-current` and its own parties block; `addPurchaser` works inside the current lot's card (every card has its own "Add party" button); the "current lot" test checks the card's indigo left border instead of a tinted cell. **No assertion about amounts, rounding, numbering or persistence was changed.** Run 2: 95 passed, 4 failed (the evidence-screenshot journeys, `add-purchaser` ambiguity). Run 3, after the second repair: 99 passed, 0 failed.
Another side effect, found and reverted: `npm run build` with a new output folder made Next add that folder to `frontend/tsconfig.json`; the entry was removed, the file equals the committed one, and the E2E build used the `.next-e2e-b` name already listed there.

### Isolation
The runs used their own database container (`mandi-e2e-pg-c`, removed afterwards), Testcontainers containers, and ports 3108, 8098 and 55456. The already running processes and containers (web 3000 and 3100, backends 8081 and 8091, databases on 5433 and 55434, containers `mandibook-postgres` and `mandi-e2e-pg`) were not touched: the same process ids were listening before and after, and no command was run against those databases. A separate development server this session had started on port 3001 was ended by the operating system's low-memory protection before these runs, not by them.

### Screenshots
The curated screenshots of the 2026-10-26 snapshot (`docs/evidence/submission-2026-10-26/`) show the removed Classic layout and its switcher (images 03 and 10 were opened and confirmed; the other bill-entry images come from the same journey) and are kept as **historical evidence only**; their README says so. The current set is `docs/evidence/submission-2026-10-30/` (15 images, including the current sign-in page), described in its own README. No old image was reused as a new one.

Not run: CI (needs a Git host), physical printer output, other browsers, load tests. Nothing here has been committed, and no ZIP has been rebuilt since 2026-10-26.

## 1d. Secret scanner: why 10 hits, and what the exit code is
**What was inconsistent.** Section 1b (2026-10-26) reported "10 hits", the 2026-10-25 records and `docs/SUBMISSION.md` said "no likely secrets", and `scripts/dev/secret-scan.js` exits 1 on any finding, which would fail the CI `guards` job (it runs the scan unfiltered; CI has never run). The two statements cannot both be true of one tree. Why the earlier runs reported none is not established: they may predate the test passwords that now match, but the repository does not show it.
**Measured on 2026-10-30, tracked files, before any change:** exit code **1**, 10 findings, all of kind "Credential assignment" (values never printed):

The last column is the **development agent's assessment, approved by the owner on 30 October 2026** (see "Approval status" below).

| Location | What it is | Agent's assessment (owner-approved 30 Oct 2026) |
|---|---|---|
| `backend/src/test/.../identity/IdentityPersistenceIntegrationTest.java:63` | password prefix plus a random UUID for a user in the test's own throw-away database | test constant |
| `.../identity/PasswordServiceTest.java:152` | sample plaintext hashed to check the bcrypt format | test constant |
| `.../security/AuthenticationFlowIntegrationTest.java:58`, `FirmContextIsolationIntegrationTest.java:41`, `OnboardingGuardsIntegrationTest.java:32`, `OnboardingIntegrationTest.java:40` | one shared synthetic test-user password (identical text in all four) in throw-away Testcontainers databases | test constant |
| `frontend/e2e/auth.spec.ts:220`, `frontend/e2e/global-setup.ts:15` | passwords of synthetic users on the disposable browser-test stack | test constants |
| `docs/AUTHENTICATION_AND_MULTI_TENANT_SECURITY_BLUEPRINT.md:166` | the text label "reset/verify" on the credential_token relationship of an entity-relationship diagram | false positive |
| `scripts/db/verify-runtime-role.sh:8` | the PGPASSWORD setting takes its value from the shell variable DB_PASSWORD (a reference, not a literal) | false positive |

**Checks.** Each line was read with the value masked; none of the 8 test values appears in `docker-compose.yml`, `application.properties`, `application-dev.properties`, `docs/RUNNING.md`, `README.md`, `docs/DB_BACKUP_AND_ROLES.md` or `scripts/e2e/stack.js` (the stack generates its own random passwords). `.env` files were not read. **No real secret was found in the current tracked files by this assessment.** The added and removed lines of all 8 commits of this repository were run through the same rules on 2026-10-30: 10 raw findings, 7 distinct matched texts, all of them the texts above and none new. The original repository's history was not examined.
**What changed.** The scanner rules were not weakened. A narrow allow-list (`scripts/dev/secret-scan-allowlist.json`) suppresses a finding only when file, finding kind and the SHA-256 of the exact matched text all equal an entry that carries a written reason; a changed value, a moved value or any new finding is reported again. Suppressed findings are counted in the output. Three new tests check this (exact match only, malformed entries refused, no stale entries). `--digests` lists findings with hashes only, to add an entry after reading the finding.
**Approval status: OWNER-APPROVED for this submission on 30 October 2026.** The project owner approved all ten existing entries (8 synthetic test passwords and 2 documented false positives) and confirmed that none of the eight test passwords is used for any real account or system. The entries were proposed by the development agent; the approval is the owner's statement in the development session, and the repository stores this record, not the transcript. The allow-list file carries the same status. The scanner prints the word "verified" in its summary line; that is fixed output text. All ten entries and their hashes are unchanged by the approval.
**Known limitation of the scanner and of these entries (disclosed, not fixed).** The scanner reports at most one finding per line and takes only the first match of a rule, and an entry matches only that first credential-like text of its line. A second credential-like assignment added to one of the ten approved lines would therefore not be examined, whereas without the allow-list the line would be flagged. A line containing a placeholder word (for example "example", "dummy", "xxx" or "test-only") is skipped entirely, independently of the allow-list. Entries are not tied to a line number, so an identical line copied elsewhere in the same file is also suppressed (harmless, same text). The same shared test value also appears in one further test file in a form the scanner does not match. Changing a value, moving it to another file, or adding a different kind of secret on the line is still reported. The scanner is therefore a safety net, **not a guarantee**; no claim is made that it finds every secret.
**Result now:** exit code **0**, "no likely secrets found (10 verified test constant(s) suppressed ...)" (on the corrected tree of §1c); 65 of 65 tooling tests pass. With the allow-list ignored the raw count is still 10. The allow-list file is new and untracked until committed.

## 1. Fresh verification of the committed snapshot `b057a7b` (2026-10-26)
Run in this repository (not the original working tree), Windows, JDK 21.0.12, Node 24.4.1, Docker. Raw summaries: `docs/evidence/verification-2026-10-26/`.

### 1b. Final run, after removing firm-specific test rates (current code)
Synthetic per-piece rates (2.5 and 1.75) are configured by the tests themselves; new assertions check that a changed configured rate changes the calculated expense. No production code changed.

| Check | Result |
|---|---|
| Backend `./mvnw -B verify` (Testcontainers, own throwaway PostgreSQL containers) | PASS: 251 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS, exit 0, jar packaged. New assertions are inside existing tests, so the count is unchanged |
| Frontend `npm test` | PASS: 53 of 53 (one new test: a changed head rate changes expenses and totals) |
| `npx tsc --noEmit` (after the build) / `npx eslint` / `npm run build` | exit 0 / exit 0 / completed |
| Playwright E2E (Chrome) on a disposable stack (own container, ports 55455 / 8097 / 3107, own fixtures file outside the repository) | PASS: 105 of 105, exit 0, 9.9 minutes (104 earlier specs plus one new: the rate of a head is changed in Masters data and the next bill's calculated expense follows) |
| `node --test` on the guard hook, secret scanner and stack shutdown tests | 62 of 62 |
| Secret scan (`scripts/dev/secret-scan.js`) | 10 hits (the scanner exits 1 on any hit), all test-only constants or false positives (values not printed); no real secret found. The inconsistency with the "no likely secrets" statements elsewhere is explained and resolved in §1d |
| Search of tracked files for the two former rates, personal paths and `scratchpad` | none left (only generic OneDrive remarks in docs) |

The first mistake of this run was mine: a new frontend unit test had a wrong hand-calculated expected total; the test caught it and the expected value was corrected before the runs above.

### 1a. Earlier fresh run, before the rate change (same day)
Backend 251 tests, frontend 52 of 52, Playwright 104 of 104 (6.7 minutes, ports 55445 / 8095 / 3105), eslint and build exit 0, `tsc --noEmit` exit 2 on a fresh tree (`LayoutProps` is generated by the build) and exit 0 after `npm run build`. Superseded by 1b.

Not run: CI (the workflow was only inspected; it needs a Git host), physical printer output, other browsers, load tests. The E2E run covers print views in English and Hindi at desktop and 390 px in a browser, not on paper. An existing disposable stack from another session (container `mandi-e2e-pg`, port 55434) was running and was not touched.

## 2. Historical results (original repository, not re-run here)

### Session 2026-10-26, fifth pass (mobile print layout, isolated packaged build)
| Check | Command | Result |
|---|---|---|
| Mobile print regression test | `e2e/print-mobile.spec.ts` (390 and 1440 px, English and Hindi) on the isolated stack | **Failed before the fix** (390 px: 9 English cells and 5 Hindi cells wider than their cells), **passes after** (4 of 4). It also asserts that every table stays inside the sheet, that no header is broken letter by letter, and that the page does not scroll sideways |
| Full browser suite, isolated stack (3101, 8092, 55435) | `npx playwright test` | **104 passed, 0 failed, 0 skipped (7.5 min)** |
| Backend, packaged, isolated copy | `./mvnw -B verify` in a temporary copy of `backend/` outside the repository, which matched the working tree file by file (178 of 178 SHA-256, tree digest 73ceae5e0f2ea8cf, including the 32 modified and 56 untracked backend files) | **BUILD SUCCESS, exit 0, 251 tests, 0 failures, 0 errors, 0 skipped, about 7.7 min.** `spring-boot:repackage` ran: `target/mandi-backend-0.0.1-SNAPSHOT.jar`, 60,285,001 bytes, `unzip -t` reports no errors, manifest `Main-Class: org.springframework.boot.loader.launch.JarLauncher`, `Start-Class: com.mandi.MandiBackendApplication`, Spring Boot 4.1.1, 420 entries, 92 libraries, contains `BillCalculator.class` and the migrations up to V13. The jar was not started |
| Backend packaged in the repository tree | | **Still unverified:** the original stack's jar is locked. The copy build above is the only packaged build |
| Frontend | `npm test` / `npx tsc --noEmit` / `npx eslint` / `npm run build` | 52 of 52 / exit 0 / exit 0 / compiled |
| Guards, scanner, shutdown | `node --test` on the three test files | 62 of 62 |

**Mobile print defect.** Cause: the global rule `th { white-space: nowrap }` (`globals.css`) also applied to the print sheet's headers, so on a 390 px screen the fixed-layout tables could not wrap their headers and the text ran into neighbouring cells; the existing `overflow-wrap: anywhere` has no effect under `nowrap`. Fix, phone screens only (`@media screen and (max-width: 600px)`, `.bill-sheet`): auto column widths, `white-space: normal`, tighter padding and header size, numbers never split. Desktop and `@media print` rules are unchanged (checked by eye at 1440 px in Hindi after a first attempt had changed desktop wrapping and was corrected). Limits: the phone preview uses 10.5 to 11.5 px text; real printer output is untested.

### Session 2026-10-26, fourth pass (shutdown safety, evidence curation, submission readiness)
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

### Session 2026-10-25/26, third pass (isolated browser stack and submission checks)
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

Screenshots of this pass (synthetic data, 44 files, `<size>-<step>.png` for sizes 1440, 1280, 1024, 390 and steps 01 to 11) were kept outside the repository; a curated subset is in `docs/evidence/submission-2026-10-26/`.

### Session 2026-10-25, second pass (calculator review and finalization)
| Check | Command | Result |
|---|---|---|
| Backend tests | `cd backend && ./mvnw -B verify -Dspring-boot.repackage.skip=true` | **BUILD SUCCESS, 250 tests, 0 failures, 0 errors, 0 skipped** (240 earlier + 9 new `BillCalculatorTest` + 1 new API test). The repackage step was skipped on purpose because another process holds the jar (see below); no test was skipped |
| Frontend | `npm test` / `npx tsc --noEmit` / `npx eslint` / `npm run build` | 52 of 52 pass / exit 0 / exit 0 / compiled |
| Guards | `node --test .claude/hooks/guard.test.js scripts/dev/secret-scan.test.js`; `node scripts/dev/secret-scan.js` | 36 of 36 pass; no likely secrets |
| Browser tests | not run | the disposable stack from `scripts/e2e/stack.js` is already running (container `mandi-e2e-pg`, backend pid 13760 on 8091, `next start` pid 28448 on 3100), started at 12:15 and 13:21 today, not by this task. The specs hard-code port 3100 and `stack.js start` would stop that stack first, so a second stack cannot run beside it. Stop it with `node scripts/e2e/stack.js stop` when its owner agrees, then follow `RUNNING.md` |

Calculator changes in this pass (no formula changed): negative weight, rate, pieces or commission base are rejected; a missing per-piece rate or missing gross or expenses is rejected instead of failing with a null error; a calculated amount above 999,999,999,999 (the limit of the `numeric(18,6)` columns) is refused with the validation message `validation.amount.tooLarge` (English and Hindi) before anything is saved. The new API test saves oversized lot and expense amounts and expects 422 with no bill number consumed. It was not run before the guard existed, so it is not shown to have failed first.

### Session 2026-10-25 (repository hygiene and AI governance)
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

### Earlier results (recorded in earlier sessions, not re-run today)
These were recorded when the work was done and describe the code of that time; the counts have since grown (see today's results).
- Original build (2026-10-17): 28 backend tests, 6 unit tests and 6 browser tests passed.
- Redesign (2026-10-19): 28 backend, 11 unit; browser 15 of 16 with one test-side failure later diagnosed and fixed.
- Audit history (2026-10-19): 35 backend, 18 unit, 27 browser; a mutation check (removing `@Transactional`) made the atomicity test fail as intended.
- Firm boundary (2026-10-19): 43 backend, 27 browser; a mutation check (ignoring the firm in a lookup) made the isolation test fail as intended; migration rollback verified.
- Navigation (2026-10-20): 28 unit, 43 browser.

### Failures and fixes (honest record)
| # | Failure | Root cause | Fix |
|---|---|---|---|
| 1 | Maven could not resolve the parent POM | generated version `4.1.1.RELEASE` does not exist | corrected to `4.1.1` |
| 2 | `pieces: 2.5` accepted and truncated to 2 | Jackson float to int coercion | `accept-float-as-int=false`, test added |
| 3 | Backend would not start on an existing database | seed was versioned; Flyway ignore patterns incomplete | repeatable idempotent seed; both `*:missing` and `*:future` patterns in the dev profile |
| 4 | A test class failed with "Unresolved compilation problems" | the IDE's Java compiler overwrote a class file | delete the generated class and rebuild; documented in `RUNNING.md` |
| 5 | One browser test failed intermittently once | unknown | not reproduced in 40 probe attempts and 6 further full runs; **still unexplained** |
| 6 | Backend packaging failed on 2026-10-25 | a running disposable stack holds the jar | not fixed; stop the stack first |

### Not verified
Real printer output; other browsers and screen readers; load with many bills; Hindi wording by a native speaker; accounting rules beyond `business-rules.md`.


## Screenshots
**Current set:** `docs/evidence/submission-2026-10-30/` holds the curated screenshots of the current interface (synthetic data; its README lists them and says where each came from).
**Historical:** `docs/evidence/submission-2026-10-26/` holds the curated screenshots of commit `b057a7b`. They were described on 2026-10-26 as the final interface; **they are no longer**: they show the since-removed Classic layout and layout switcher (see its README). They are kept unchanged as historical evidence. Earlier UI-iteration folders, including a temporary layout switcher and captures with an overlapping sidebar, were removed from this repository on 2026-10-26 because they no longer showed the interface of that day; they remain only in the original development repository.
