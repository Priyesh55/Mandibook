# Open Decisions and Their Effect on the Product

What is not decided, and what the application does meanwhile. Implemented rules are in `business-rules.md`; technical assumptions are in `ASSUMPTIONS.md`.

| ID | Open decision | What the application does meanwhile |
|---|---|---|
| BR-101 | Rounding tie rule | Half-up |
| BR-102 | Second per-piece rate for heavy pieces | Not modelled |
| BR-103 | Meaning of the final total; payables and receivables | None produced or stored |
| BR-104 | Ledger, voucher and journal postings | Not built (deferred) |
| BR-105 | Edit and cancel after save | Bills are view and print only |
| BR-106 | Meaning of a cash bill | A label without accounting effect |
| BR-107 | Print layout | Draft layout |
| BR-108 | Per-firm rule differences | One rule set |
| Privacy | How a person's name could be anonymized on request; audit retention is **permanent** (owner decision) | The history is append-only and stores names; decide erasure before real data is stored |
| Security | Per-role permission policy beyond OWNER/EMPLOYEE; password reset, MFA, email delivery in production | Roles are enforced for firm profile, numbering and team; no reset or MFA yet |

## What would settle an accounting rule
One worked example per rule (inputs and expected outputs) approved by the owner. It becomes a row in `business-rules.md`, a test vector, then a rule-set change in `BillCalculator`.
