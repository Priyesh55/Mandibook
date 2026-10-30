# Business-Rule Register

The authoritative list of what Mandi Book calculates and what it deliberately does not. A rule is implemented only when it is listed here with a status below and is covered by a test. Amounts are calculated by the server in exactly one place, `BillCalculator` (`backend/src/main/java/com/mandi/billing/aadat`); the browser mirrors the arithmetic for live preview only (`frontend/src/lib/billDraft.ts`, `decimal.ts`) and the server stores its own result. Unit vectors: `BillCalculatorTest`; API behaviour: `BillWorkflowApiIntegrationTest`.

## Status values
- **Confirmed** (owner, 2026-10-25): the project owner personally approved the rule on 2026-10-25, in the AI-assisted development session in which this register was updated. The approval is a message in that session; the repository stores this record, not the transcript. The owner's explicit approval covers exactly three rules: the 5 % commission rate, the per-100-kg rate basis, and per-piece expense calculation.
- **Confirmed (owner, 2026-10-30)**: on 2026-10-30 the owner further confirmed, in a development session, how the per-piece expense is worked out: one expense-head rate applies across all lots (BR-005c), the expense is calculated and rounded per lot (BR-005a, BR-005b), and the lot amounts are summed. Same kind of record as above (a session message; the repository stores this record, not the transcript). **The current implementation does not follow the per-lot rounding of BR-005b for some rates: see "Known implementation limitations".**
- **Recorded, not covered by the explicit approval**: implemented because the project instructions record it as stated by the owner (rule set `OWNER_2026_10_25_Q100`), but the owner's explicit approval of 2026-10-25 did not name it (rounding precision, who bears the commission, net). It stays implemented as before; confirm each with one worked example to promote it to Confirmed.
- **Design decision**: a product choice, not an accounting formula.
- **Unresolved**: not implemented.

## Confirmed
| ID | Rule | Example | Status |
|---|---|---|---|
| BR-001 | The rate is entered **per 100 kg**; the value of a lot is weight (kg) x rate / 100 | a rate of 30 per kg is entered as 3000; 250 kg at 3000 gives 7500 | Confirmed (owner, 2026-10-25) |
| BR-004a | Commission is a fixed **5 %** of the purchaser value | purchaser value 900 gives 45 | Confirmed (owner, 2026-10-25) |
| BR-005a | An expense head with a predefined rate per piece costs **pieces x rate per piece**, worked out lot by lot: each lot's expense is that lot's own pieces x the rate, and the expense of the bill is the sum of the lots' amounts. Every other expense is typed | 10 pieces at 2 gives 20; lots of 20 and 15 pieces at a configured 2.5 give 50.00 and 37.50, together 87.50 | Confirmed (owner, 2026-10-25); the lot-by-lot wording was confirmed by the owner on 2026-10-30 (see the note below the table) |
| BR-005b | The per-piece expense is calculated and **rounded half-up to two decimals per lot**, and the rounded lot amounts are then **summed** to give the expense of the bill | two lots of 1 piece each at a configured rate of 0.005 give 0.01 + 0.01 = 0.02 | Confirmed (owner, 2026-10-30). **Not followed by the current implementation for some rates (LIM-1)** |
| BR-005c | **One expense-head rate applies across all lots** of a bill: the rate belongs to the expense head (configured in Masters), not to a lot or an item | a head configured at 2.5 prices a lot of 20 pieces at 50.00 and a lot of 15 pieces at 37.50 | Confirmed (owner, 2026-10-30); this is how the application already works |
| BR-014 | **Per-piece expense rates are configuration, not rules.** A rate belongs to one firm's expense head (master data entered in Masters), with the basis `PER_PIECE`. The application ships no rate and no default; a head without a rate is a typed expense, and a rate without its basis (or the reverse) is refused. | A head configured with a synthetic rate of 2.5: 35 pieces give 87.50; reconfigured to 3, the next bill gives 105.00 | Recorded |

**Confirmation of 2026-10-30 (BR-005a, BR-005b, BR-005c).** The owner confirmed that one expense-head rate applies across all lots, that each lot's expense is calculated independently as lot size x rate and rounded per lot, and that the lots' amounts are summed to give the expense of the bill. The existing implementation and tests were inspected against this wording and were **not changed**. The server (`AadatBillService`, which calls `BillCalculator.perPieceExpense`) and the browser mirror (`billDraft.ts`) add up the pieces of all lots first, multiply the total by the head's rate and round **once**. That gives exactly the confirmed result whenever the rate has at most two decimals, because the product of whole pieces and such a rate is exact (the two-lot tests, 20 + 15 pieces at configured rates of 2.5 and 1.75, give 87.50 and 61.25 either way). It can differ from the confirmed rule for rates with three or four decimals, which the application accepts; that is a known implementation limitation (LIM-1 below), not a compliance claim. Only the interpretation of "lot size" is still open (BR-110). The approval is a message in the development session; the repository stores this record, not the transcript.

**Scope of BR-014.** A rate is data of one firm's expense head, never a default: the starter expense heads carry no rate, nothing in the code assumes any particular rate, and every firm sets its own in Masters. Tests and fixtures configure clearly synthetic rates themselves and verify that changing a configured rate changes the next bill's expense; a saved bill keeps the rate it was calculated with. Earlier working drafts of this project used two per-piece figures that the owner had described as real rates of the firm; they were replaced by synthetic values in the submission copy (see `docs/PROVENANCE.md` §3).

## Recorded, not covered by the explicit approval
| ID | Rule | Example | Status |
|---|---|---|---|
| BR-002 | Lot value is rounded half-up to a whole rupee, with one rounding at the end | 333 kg at 1937 gives 6450 (6450.21) | Recorded |
| BR-003 | A purchaser's value is computed the same way from the purchaser's own weight and rate | as BR-002 | Recorded |
| BR-004b | Commission is rounded half-up to one decimal, charged to the buyer and never deducted from the farmer | 3 gives 0.2 (0.15) | Recorded |
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
| BR-110 | Edge of the lot-by-lot per-piece rule that remains open: (c) whether "lot size" always means pieces (bags), the only basis modelled. **Closed as owner-confirmed on 2026-10-30:** (a) whether different lots may carry different rates (no: one head rate applies across all lots, BR-005c) and (b) whether rounding is per lot or once per bill (per lot, then summed, BR-005b) | Basis `PER_PIECE` only; lot size is taken as pieces. The rounding of (b) is not what the code does for some rates: see LIM-1 |

The entered totals shown for older bills are plain sums of typed amounts and are not accounting calculations.

## Known implementation limitations
These are places where the running code differs from a confirmed rule or does less than the register describes. They are disclosed, not hidden, and the application must not be described as fully compliant with the rules they concern.

**LIM-1: per-piece expense rounding differs from BR-005b for some rates.**
- **Confirmed rule (BR-005b):** the expense of each lot is its pieces x the head's rate, rounded half-up to two decimals, and the rounded lot amounts are summed.
- **Current implementation:** the server and the browser preview add the pieces of all lots first, multiply the total by the head's rate and round half-up **once** (`AadatBillService`: `totalPieces`, then `BillCalculator.perPieceExpense(totalPieces, rate)`; `frontend/src/lib/billDraft.ts`: `expenseAmount` on `totalPieces`). The saved expense line therefore stores the bill's total pieces and the rate, not one line per lot. Code comments (`AadatBillService`, the `ExpenseHead` class comment, the `V13` migration comment) still describe "the pieces of the bill".
- **When the results are the same:** whenever the rate has at most two decimals. The product of whole pieces and such a rate is exact at two decimals, so rounding per lot or once gives the same amount.
- **When they can differ:** for rates with three or four decimals. The application accepts up to four (the Masters form accepts `\d{1,4}` decimals, the server validates `Digits(fraction=4)`, the column is `numeric(18,6)`), so a user can enter such a rate.
- **Known example:** two lots of one piece each and a configured rate of 0.005. Current implementation: 2 pieces x 0.005 = 0.010, rounded once, gives **0.01**. Confirmed rule: each lot 1 x 0.005 = 0.005 rounds half-up to 0.01, and the sum is **0.02**.
- **Size:** at most half a paisa per lot per expense head. The farmer's net is rounded to the rupee afterwards, so the effect on the net is limited to rare edge cases; it is not claimed to be zero.
- **Tests:** `BillCalculatorTest` checks the single-call rounding (for example 1 piece at 0.005 gives 0.01), and the two-lot tests use rates with at most two decimals. **No test covers a multi-lot expense at a rate with three or four decimals.**
- **Status:** known limitation, **not fixed**; no calculation or test was changed for this submission. A correction would calculate and round per lot in `BillCalculator`, the service and `billDraft.ts` with new test vectors and a rule-set id, and needs the owner's go-ahead.

## Adding a rule
1. Add a row here with a worked example (inputs and outputs) and its source.
2. Write the test vector first (`BillCalculatorTest`).
3. Implement it in `BillCalculator` only, with a new rule-set id.
