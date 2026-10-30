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
| BR-110 | Whether "lot size" of the per-piece expense always means pieces (bags). The other two edges were **closed as owner-confirmed on 2026-10-30**: one head rate applies across all lots, and the expense is rounded per lot, then summed | Basis `PER_PIECE` only; lot size is taken as pieces |
| LIM-1 | **Known implementation limitation, not an open decision:** the confirmed per-lot rounding (BR-005b) is not what the code does. The server and the browser preview add the pieces of all lots and round once | Identical results for rates up to two decimals; for rates with three or four decimals the amount can differ by up to half a paisa per lot (two lots of one piece at a rate of 0.005 give 0.01 now and 0.02 under the confirmed rule). Not fixed; see `business-rules.md` "Known implementation limitations" |
| Haste | Meaning of the free-text "Haste" field on purchaser rows | Stored and shown; nothing is calculated from it |
| Privacy | How a person's name could be anonymized on request; audit retention is **permanent** (owner decision) | The history is append-only and stores names; decide erasure before real data is stored |
| Security | Per-role permission policy beyond OWNER/EMPLOYEE; password reset, MFA, email delivery in production | Roles are enforced for firm profile, numbering and team; no reset or MFA yet |

## What would settle an accounting rule
One worked example per rule (inputs and expected outputs) approved by the owner. It becomes a row in `business-rules.md`, a test vector, then a rule-set change in `BillCalculator`.
