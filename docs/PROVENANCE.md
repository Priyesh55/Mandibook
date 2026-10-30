# Provenance, prior work and history boundary

This note says where Mandi Book came from, what influenced it, and what the history of this repository does and does not show. It is written to be accurate rather than flattering.

## 1. Prior work: the original development repository
Mandi Book was developed with AI assistance (Claude Code) in an earlier private repository, "Mandi Business App" (10 commits, 2026-10-17 to 2026-10-20, plus a larger body of uncommitted work completed between 2026-10-20 and 2026-10-26; these figures come from the project's earlier records and cannot be verified from this repository). **This repository is a snapshot of that working tree, not a continuation of its Git history, and it does not contain the complete original development history.** The submission copy was first prepared on 2026-10-26 and corrected afterwards. Its history has 10 commits: 8 dated 2026-10-26 (the snapshot and the work on the copy) and 2 dated 2026-10-30 (the final submission commit `124a1c5` and a documentation commit that records it; `docs/QA_EVIDENCE.md` section 1c).

### History boundary
- The first commit here (`chore: import MVP working-tree snapshot ...`) records the state of the original **working tree**, including files that were modified or untracked and never committed there (authentication, firm context, billing workflow, V5 to V13 migrations, most browser tests, the AI guardrails). Commits after it are genuine work done on the submission copy, with their real timestamps.
- The 10 original commits are **not** reproduced, rewritten or imitated here. Their dates and messages are not claimed as part of this history. The original repository is held by the project owner and can be shown to organizers on request; its current state was not examined for this submission.
- Reason for not carrying the history over: the original history contains investigation documents about another vendor's desktop product (see section 2), which are not needed to understand or run this application.
- Consequence: this repository's Git log **does not show the full development history**. If the hackathon requires the complete original history, the original repository (or a copy of its `.git`) must be supplied instead; that decision rests with the organizers and the project owner.

## 2. Reference-informed concepts (disclosure)
The product owner is the operator of a family aadat business. Early in the project the team investigated an older desktop billing program used in that business, to understand the domain. **The full scope of that investigation, and the provenance of what it produced, have not been independently established, and the project owner has told us they are unsure of both.**

What has been reported, and cannot be confirmed or excluded from this repository: a provenance review described by the project owner said the investigation included
- inspection of **database table and column identifiers** of the older program,
- investigation of its **decompiled source**,
- **photographs of other firms' screens** of the older program.

The same review reported finding no source code and no compiled binaries of the older program in the original repository or its history. We do not treat that report as authoritative: the owner is not certain of the scope it covered, and the investigation documents existed only in the original repository's history, which is not part of this submission. Documents in that original history are also inconsistent about whether the program was decompiled; this cannot be resolved from this repository, and neither version should be relied on.

**What this repository does show (2026-10-30), and its limits.** The name of the older program does not appear in the files of this repository or in any of its 8 commits, and no file in any commit is named like an investigation, discovery or decompilation note. The seed and test data are fictitious, and a secret scan found no credentials or real business records (`docs/QA_EVIDENCE.md` section 1d). These were text and file-name searches only. No binary, decompiler or similarity analysis was done, so they cannot show that nothing was derived from or copied from the older program, and **we make no categorical statement either way.** Anyone relying on this note should treat provenance as unverified and the owner's confirmation as outstanding.

The following concepts were, or may have been, **informed by that reference** (the owner's earlier review lists them as such) and must not be described as independently derived; the full extent of the influence is not established:

| Area in Mandi Book | Reference-informed? |
|---|---|
| Bill structure: header, lots, purchaser allocations, expenses | Informed by the older program's record layout and the owner's domain knowledge |
| Four optional reference numbers on a bill (book, receipt, entry, WS number), stored as typed, meaning unconfirmed (`V11__aadat_workflow.sql`) | Informed by the older program's header fields; not required, no accounting effect |
| Free-text "Haste" field on purchaser rows | Informed by the older program; **its meaning is unresolved** and nothing computes from it |
| Configurable starter expense heads | Informed by the older program's expense master; heads carry no rates by default |
| 5 % commission charged to the buyer; rate per 100 kg; rounding half-up (BR-002 to BR-006, BR-004b) | Business rules confirmed or recorded by the product owner (`business-rules.md`). Their origin in the older program's behaviour cannot be excluded, so they are disclosed as reference-informed. The tie rule and a second per-piece rate are unconfirmed |
| Lot-mark (*marka*) field, village-first farmer selection, continuous per-firm bill numbering across financial years (1 April to 31 March) | Domain practice of the owner's firm, possibly also visible in the older program |
| Screen layout and printed bill | Own design (white-and-blue theme, keyboard grids); informed in general terms by what counter staff already know. The print layout is a draft (BR-107) |

Data: the seed is fictitious (`R__synthetic_demo_data.sql`), tests and screenshots use synthetic names, and the searches and scan above found no real firm, farmer, buyer or bill and no credentials in this repository. As stated above, this does not establish provenance.

Leftover references: `V1__baseline_schema.sql` still contains a comment that points to a document (`LEGACY_BEHAVIOR_FINDINGS.md`) that is not part of this submission. It was left unchanged because editing an applied Flyway migration changes its checksum.

## 3. Business figures in tests
Per-piece expense rates are master data that each firm configures; the application contains no firm-specific rate. Tests and fixtures in this submission configure clearly synthetic rates themselves (for example 2.5 and 1.75) and check that changing a configured rate changes the calculated expense (`BillCalculatorTest`, `BillWorkflowApiIntegrationTest`, `billDraft.test.ts`, `aadat-mvp-journey.spec.ts`).

**Disclosure.** In the working drafts before this submission, tests, `docs/business-rules.md` and `docs/ai-log.md` used two small per-piece figures that the owner had described as real rates of the firm. They were removed from the files of this repository in the commits after the snapshot (the test change and the audit-fix commit of 2026-10-26). This repository's first commit (the working-tree snapshot) and the original development repository still contain them, and this repository's history was deliberately not rewritten. Whether those two figures are confidential is for the owner to decide; the files of the submitted tree no longer contain them.

## 4. AI contribution
Almost all code, tests and documents were produced by an AI agent (Claude Code, model recorded in commit trailers) in sessions directed and reviewed by the project owner, who made the product and business-rule decisions. See `docs/ai-log.md` (which also records AI mistakes) and `CLAUDE.md` / `docs/AI_POLICY.md` for the controls used. Prompts and transcripts are not stored.

**Second AI tool: ChatGPT (OpenAI).** ChatGPT supported planning, architecture discussions, prompt preparation and technical review, alongside Claude Code, which did the implementation. **The project owner confirmed on 30 October 2026 that this description is accurate.** This repository holds no ChatGPT conversation, prompt or output, so the use cannot be verified from it, and nothing in it shows ChatGPT writing or committing application code; no such claim is made. The commit trailers name Claude only.

## 5. Third-party material
We know of no third-party library source vendored in the repository: dependencies are declared in `backend/pom.xml` and `frontend/package.json`; see `docs/THIRD_PARTY_NOTICES.md`. This has not been checked with a licence or provenance scanner, and it says nothing about the older program in section 2.
