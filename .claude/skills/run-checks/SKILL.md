---
name: run-checks
description: Run the project's backend and frontend checks and report the real results. Use before reporting a task as done, or to establish a baseline before changing code.
argument-hint: "[backend|frontend|guards|all; default: all]"
disable-model-invocation: true
allowed-tools: Bash(./mvnw*), Bash(npm test), Bash(npx tsc --noEmit), Bash(npx eslint*), Bash(npm run build), Bash(node --test*), Bash(node scripts/dev/secret-scan.js*), Read, Grep
---

# Run the checks

Input: `$ARGUMENTS` (default `all`).

## Safety
- Synthetic data and disposable databases only. Never connect to `mandi_demo` and never start the backend against it. Backend tests use Testcontainers (Docker must be running).
- Do not run `npm run build` while a `next start` is serving `frontend/.next`.
- JDK 21 is required: set `JAVA_HOME` for the command (`CLAUDE.md`), never change system Java.
- Browser tests (`npm run test:e2e`) need the disposable stack (`node scripts/e2e/stack.js start`, then `stop`); run them only when the user asks.

## Steps
1. `guards`: `node --test .claude/hooks/guard.test.js scripts/dev/secret-scan.test.js` and `node scripts/dev/secret-scan.js`.
2. `backend`: `cd backend && ./mvnw -B verify`.
3. `frontend`: `cd frontend && npm test`, `npx tsc --noEmit`, `npx eslint`, `npm run build`.
4. On a failing check: at most 3 repair attempts, then stop and report the blocker with hypotheses.

## Output
A table of check, exact command, result (counts of passed, failed, skipped tests) and anything not run, with the reason. Quote real output; never write "passed" for a check you did not run in this session.
