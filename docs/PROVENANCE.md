# Provenance, prior work and history boundary

This note says where Mandi Book came from, what influenced it, and what the history of this repository does and does not show. It is written to be accurate rather than flattering.

## 1. Prior work: the original development repository
Mandi Book was developed with AI assistance (Claude Code) in an earlier private repository, "Mandi Business App" (10 commits, 2026-10-17 to 2026-10-20, plus a larger body of uncommitted work completed between 2026-10-20 and 2026-10-26). **This repository is a snapshot of that working tree, not a continuation of its Git history.** The submission was prepared on 2026-10-26.

### History boundary
- The first commit here (`chore: import MVP working-tree snapshot ...`) records the state of the original **working tree**, including files that were modified or untracked and never committed there (authentication, firm context, billing workflow, V5 to V13 migrations, most browser tests, the AI guardrails). Commits after it are genuine work done on the submission copy, with their real timestamps.
- The 10 original commits are **not** reproduced, rewritten or imitated here. Their dates and messages are not claimed as part of this history. The original repository still exists, unchanged, and can be shown to organizers on request.
- Reason for not carrying the history over: the original history contains investigation documents about another vendor's desktop product (see section 2), which are not needed to understand or run this application.
- Consequence: this repository's Git log **does not show the full development history**. If the hackathon requires the complete original history, the original repository (or a copy of its `.git`) must be supplied instead; that decision rests with the organizers and the project owner.

## 2. Reference-informed concepts (disclosure)
The product owner is the operator of a family aadat business. Early in the project the team investigated an older desktop billing program used in that business, to understand the domain. According to the project owner's provenance review, this investigation included:
- inspection of **database table and column identifiers** of the older program,
- investigation of its **decompiled source**,
- **photographs of other firms' screens** of the older program.

The provenance review found **no source code and no compiled binaries of the older program in this or the original repository or its history**. The investigation documents themselves existed only in the original repository history and are not included here. Some documents in the original repository's history state that the older program was not decompiled; that statement conflicts with the review above, is not repeated in this submission and should not be relied on. Treat the review's description as authoritative, and the exact scope of the investigation as something the project owner must confirm. Nothing in this repository was copied from the older program, but the following concepts were **informed by that reference** and must not be described as independently derived:

| Area in Mandi Book | Reference-informed? |
|---|---|
| Bill structure: header, lots, purchaser allocations, expenses | Informed by the older program's record layout and the owner's domain knowledge |
| Four optional reference numbers on a bill (book, receipt, entry, WS number), stored as typed, meaning unconfirmed (`V11__aadat_workflow.sql`) | Informed by the older program's header fields; not required, no accounting effect |
| Free-text "Haste" field on purchaser rows | Informed by the older program; **its meaning is unresolved** and nothing computes from it |
| Configurable starter expense heads | Informed by the older program's expense master; heads carry no rates by default |
| 5 % commission charged to the buyer; rate per 100 kg; rounding half-up (BR-002 to BR-006, BR-004b) | Business rules confirmed or recorded by the product owner (`business-rules.md`). Their origin in the older program's behaviour cannot be excluded, so they are disclosed as reference-informed. The tie rule and a second per-piece rate are unconfirmed |
| Lot-mark (*marka*) field, village-first farmer selection, continuous per-firm bill numbering across financial years (1 April to 31 March) | Domain practice of the owner's firm, possibly also visible in the older program |
| Screen layout and printed bill | Own design (white-and-blue theme, keyboard grids); informed in general terms by what counter staff already know. The print layout is a draft (BR-107) |

Not done: no data of the older program, no credentials, no real firm, farmer, buyer or bill is in the code, tests, seed or screenshots. The seed is fictitious (`R__synthetic_demo_data.sql`).

Leftover references: `V1__baseline_schema.sql` still contains a comment that points to a document (`LEGACY_BEHAVIOR_FINDINGS.md`) that is not part of this submission. It was left unchanged because editing an applied Flyway migration changes its checksum.

## 3. Business figures in tests
Per-piece expense rates are master data that each firm configures; the application contains no firm-specific rate. Tests and fixtures in this submission configure clearly synthetic rates themselves (for example 2.5 and 1.75) and check that changing a configured rate changes the calculated expense (`BillCalculatorTest`, `BillWorkflowApiIntegrationTest`, `billDraft.test.ts`, `aadat-mvp-journey.spec.ts`).

**Disclosure.** In the working drafts before this submission, tests, `docs/business-rules.md` and `docs/ai-log.md` used two small per-piece figures that the owner had described as real rates of the firm. They were removed from the files of this repository in the commit that follows the snapshot. This repository's first commit (the working-tree snapshot) and the original development repository still contain them, and this repository's history was deliberately not rewritten. Whether those two figures are confidential is for the owner to decide; the files of the submitted tree no longer contain them.

## 4. AI contribution
Almost all code, tests and documents were produced by an AI agent (Claude Code, model recorded in commit trailers) in sessions directed and reviewed by the project owner, who made the product and business-rule decisions. See `docs/ai-log.md` (which also records AI mistakes) and `CLAUDE.md` / `docs/AI_POLICY.md` for the controls used. Prompts and transcripts are not stored.

## 5. Third-party material
No third-party source code is vendored. Dependencies are declared in `backend/pom.xml` and `frontend/package.json`; see `docs/THIRD_PARTY_NOTICES.md`.
