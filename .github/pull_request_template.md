## What and why
<!-- One or two sentences. Link the decision or open question it relates to (docs/business-rules.md, docs/UNRESOLVED.md). -->

## Checklist
**Implementation**
- [ ] Amounts are calculated only in `BillCalculator`, per `docs/business-rules.md`; no new business rule was invented
- [ ] Every new `/api/**` route is authorized server-side; the firm never comes from the request
- [ ] State changes call `AuditService.record` in the same transaction

**Tests**
- [ ] Tests added or updated for the behaviour change (name them below)
- [ ] No test skipped, disabled or weakened
- [ ] Backend `./mvnw -B verify`, frontend `npm test`, `npx tsc --noEmit`, `npx eslint`, `npm run build` run locally, results below

**Security and data**
- [ ] No secrets, `.env` content, backups or non-synthetic data added
- [ ] `node scripts/dev/secret-scan.js` is clean

**Migrations** (delete if none)
- [ ] New `V<n>` plus `db/rollback/U<n>`, grants in `R__runtime_grants.sql`, `RuntimePrivilegesIntegrationTest` and `PlatformBoundaryIntegrationTest` updated
- [ ] Verified on a disposable database; `mandi_demo` untouched

**Evidence**
- [ ] Browser tests run against the disposable stack, or the reason they were not
- [ ] Screenshots added under `docs/evidence/` use synthetic data only
- [ ] `docs/ai-log.md` updated if AI produced or reviewed the change

## Test evidence
<!-- Paste commands and real results (counts of passed, failed, skipped). -->

## AI assistance
<!-- Which tool and model, what it did, what a human reviewed or decided. -->
