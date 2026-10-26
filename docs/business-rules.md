# Business-Rule Register

The authoritative list of what Mandi Book calculates and what it deliberately does not. A rule is implemented only when it is listed here with a status below and is covered by a test. Amounts are calculated by the server in exactly one place, `BillCalculator` (`backend/src/main/java/com/mandi/billing/aadat`); the browser mirrors the arithmetic for live preview only (`frontend/src/lib/billDraft.ts`, `decimal.ts`) and the server stores its own result. Unit vectors: `BillCalculatorTest`; API behaviour: `BillWorkflowApiIntegrationTest`.

## Status values
- **Confirmed** (owner, 2026-10-25): the project owner personally approved the rule on 2026-10-25, in the AI-assisted development session in which this register was updated. The approval is a message in that session; the repository stores this record, not the transcript. The owner's explicit approval covers exactly three rules: the 5 % commission rate, the per-100-kg rate basis, and per-piece expense calculation.
- **Recorded, not covered by the explicit approval**: implemented because the project instructions record it as stated by the owner (rule set `OWNER_2026_10_25_Q100`), but the owner's explicit approval of 2026-10-25 did not name it (rounding precision, who bears the commission, net). It stays implemented as before; confirm each with one worked example to promote it to Confirmed.
- **Design decision**: a product choice, not an accounting formula.
- **Unresolved**: not implemented.

## Confirmed
| ID | Rule | Example | Status |
|---|---|---|---|
| BR-001 | The rate is entered **per 100 kg**; the value of a lot is weight (kg) x rate / 100 | a rate of 30 per kg is entered as 3000; 250 kg at 3000 gives 7500 | Confirmed (owner, 2026-10-25) |
| BR-004a | Commission is a fixed **5 %** of the purchaser value | purchaser value 900 gives 45 | Confirmed (owner, 2026-10-25) |
| BR-005a | An expense head with a predefined rate per piece costs **pieces x rate per piece**; every other expense is typed | 10 pieces at 2 gives 20 | Confirmed (owner, 2026-10-25) |
| BR-014 | **Per-piece expense rates are configuration, not rules.** A rate belongs to one firm's expense head (master data entered in Masters), with the basis `PER_PIECE`. The application ships no rate and no default; a head without a rate is a typed expense, and a rate without its basis (or the reverse) is refused. | A head configured with a synthetic rate of 2.5: 35 pieces give 87.50; reconfigured to 3, the next bill gives 105.00 | Recorded |

**Scope of BR-014.** A rate is data of one firm's expense head, never a default: the starter expense heads carry no rate, nothing in the code assumes any particular rate, and every firm sets its own in Masters. Tests and fixtures configure clearly synthetic rates themselves and verify that changing a configured rate changes the next bill's expense; a saved bill keeps the rate it was calculated with. Earlier working drafts of this project used two per-piece figures that the owner had described as real rates of the firm; they were replaced by synthetic values in the submission copy (see `docs/PROVENANCE.md` §3).

## Recorded, not covered by the explicit approval
| ID | Rule | Example | Status |
|---|---|---|---|
| BR-002 | Lot value is rounded half-up to a whole rupee, with one rounding at the end | 333 kg at 1937 gives 6450 (6450.21) | Recorded |
| BR-003 | A purchaser's value is computed the same way from the purchaser's own weight and rate | as BR-002 | Recorded |
| BR-004b | Commission is rounded half-up to one decimal, charged to the buyer and never deducted from the farmer | 3 gives 0.2 (0.15) | Recorded |
| BR-005b | A per-piece expense is rounded half-up to two decimals; the pieces are the pieces of the whole bill | 1 piece at a configured rate of 0.005 gives 0.01 (half up) | Recorded |
| BR-006 | Farmer gross = sum of lot values; farmer net = gross - expenses, rounded half-up to a whole rupee; a negative net is kept | gross 900, expenses 20 gives 880 | Recorded |

## Design decisions
| ID | Rule |
|---|---|
| BR-007 | Farmer side and buyer side are never added together; print shows the farmer side only |
| BR-008 | Bill numbers form one continuous series per firm across financial years, assigned at save; the owner may set the first number before the first bill |
| BR-009 | The financial year runs 1 April to 31 March |
| BR-010 | Freight (bhada) entered in the bill details is stored as an expense of the head "Motor Bhada" |
| BR-011 | Purchaser rows record what each purchaser actually bought, as typed; nothing is split automatically; a difference from the lot is only a warning |
| BR-012 | Bills saved before these rules existed keep their typed amounts and the status `NOT_CALCULATED` |
| BR-013 | Input safety: weight, rate and pieces must not be negative; a lot or purchaser without weight and rate is refused, not guessed; a calculated amount beyond 999,999,999,999 is refused with a validation error and nothing is saved; a per-piece expense on a bill without pieces is refused |

Every bill stores its calculation status (`CALCULATED`, `NOT_CALCULATED`, or the opt-in `DEMO_UNVERIFIED`) and a rule-set id (`OWNER_2026_10_25_Q100` for current bills). `DEMO_UNVERIFIED` exists only when the server flag `mandi.calculation.demo-enabled` is set (development profiles), is never persisted as amounts and never appears in lists or reports.

## Unresolved (not implemented; the approval above does not cover them)
| ID | Question | Behaviour meanwhile |
|---|---|---|
| BR-101 | Tie rule for rounding (half-up, away from zero for negatives, is the default) | Half-up |
| BR-102 | A second per-piece rate for heavy pieces | Not modelled |
| BR-103 | The meaning of the final total in accounting terms; who owes whom | No payable or receivable is produced |
| BR-104 | Ledger, voucher and journal postings | Not built |
| BR-105 | Edit and cancel of a saved bill | Saved bills are view and print only |
| BR-106 | The meaning of a cash bill | A label with no accounting effect |
| BR-107 | Final print layout (firm header, signatures, amount in words, paper size) | Draft layout |
| BR-109 | Which per-piece rate, if any, a firm's particular expense heads should carry | Not a product rule: each firm configures its own heads and rates in Masters; the application proposes none |
| BR-108 | Whether rules may differ per firm, and any customer-specific setting | One rule set for all firms; no customer-specific setting |

The entered totals shown for older bills are plain sums of typed amounts and are not accounting calculations.

## Adding a rule
1. Add a row here with a worked example (inputs and outputs) and its source.
2. Write the test vector first (`BillCalculatorTest`).
3. Implement it in `BillCalculator` only, with a new rule-set id.
