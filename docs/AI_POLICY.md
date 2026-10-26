# AI Policy for Mandi Book Development

Scope: how AI coding agents (currently Claude Code) are used on this repository. It describes what the repository enforces and what remains the project owner's responsibility. It is a working policy for this project, not an organisational or legal approval.

## 1. Workflow
Plan, write the test for a rule first, implement in small steps, run the checks, get an independent read-only review, fix, record. Tools in the repository:
- `CLAUDE.md` holds the hard rules and commands; `.claude/settings.json` holds permission rules and the PreToolUse hook `.claude/hooks/guard.js`.
- Skills: `/review` (read-only review), `/run-checks` (runs checks, reports real results), `/new-migration` (migration with rollback, grants and tests, disposable database only). The `reviewer` subagent is a read-only reviewer.
- CI (`.github/workflows/ci.yml`) and the pull request template (`.github/pull_request_template.md`).

## 2. Data classes
| Class | Examples | Rule for agents |
|---|---|---|
| Synthetic | demo seed, fixtures, e2e accounts, screenshots | Allowed |
| Source and documentation | `backend/`, `frontend/`, `docs/`, `scripts/` | Allowed |
| Secrets | `.env`, `.env.local`, tokens, passwords, keys | Never read, print or commit (permission deny rules and the hook enforce this for the agent tools) |
| Real business data | any real firm, farmer, buyer or bill; database files, dumps, `backups/` | Never read, query, copy or upload; not used for development or tests |

## 3. Secrets and credentials
Only `.env.example` is tracked; local values live in the git-ignored `.env.local`. Never paste a secret into a prompt, log or report. `scripts/dev/secret-scan.js` runs in CI and, if enabled (`git config core.hooksPath .githooks`), before every commit. Gitleaks is used by the hook when installed.

## 4. Actions that need a human
Commit, push, merge or rebase; any change to `.claude/`, `.github/` or an applied migration; applying any migration to the persistent development database `mandi_demo` (needs the owner's go-ahead and a verified backup); starting or stopping Docker services; installing dependencies; any accounting rule. Prohibited outright for the agent: force-push, `git reset --hard`, `git clean`, `--no-verify`, `rm -rf` outside build folders, destructive SQL, permission bypass modes, reading secrets or backups. These are denied in `.claude/settings.json` and blocked by the hook.

## 5. Testing and review
A change is not done until the check ladder in `CLAUDE.md` has been run and the real results are recorded: backend `verify`, frontend tests, type check, lint, build, browser tests on the disposable stack. Bounded repair: at most three attempts per failing check, then report the blocker. AI-produced changes are reviewed by a human before merge; an AI review (`/review`) supplements, and never replaces, that review. Never claim a check passed that was not run.

## 6. Evidence
`docs/ai-log.md` records what AI did, what failed and what humans decided. `docs/QA_EVIDENCE.md` records commands and results. Screenshots live in `docs/evidence/` and use synthetic data.

## 7. Provenance, licensing and intellectual property
Every part of the product is written for Mandi Book from its own requirements (`docs/business-rules.md`). Do not paste proprietary or third-party source or documentation into the repository, prompts or tests; use only synthetic examples and rules the owner has stated. Third-party libraries keep their own licences, and dependencies suggested by an agent must be verified by a human before they are added. The repository does not make a legal determination about the ownership or licensing of AI-generated code; that is for the project owner to decide.

## 8. Attribution
Commits made with AI assistance end with the co-author trailer. Authorship trailers are never edited to change how the project looks.

## 9. Known limitations and owner responsibilities
- Not established here, and not claimed: organisational approval of AI tools, approved accounts, data-retention terms of the AI provider, regulatory mapping, a named code owner and backup, hosting settings such as branch protection and required checks.
- The hook and permission rules reduce risk but are not a sandbox. Sandboxing was not configured or verified on this Windows host.
- Hindi wording written with AI help needs a native-speaker review.
- Accounting rules beyond `docs/business-rules.md` are unconfirmed and must stay unimplemented until the owner approves them with worked examples.
