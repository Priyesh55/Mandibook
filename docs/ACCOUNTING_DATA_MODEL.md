# Accounting Data Model: Review and Proposal

Status: **review of what exists + design proposal. The proposal is NOT implemented**, because the rules it depends on are unconfirmed (`business-rules.md`). Building it now would mean inventing accounting behaviour.

## 1. What happens today when a bill is saved
`AadatBillService.create` runs in one transaction and writes:
1. `aadat_bill` (date, farmer, village, vehicle, bill number, calculation status, rule-set id, firm),
2. `aadat_lot` and `aadat_lot_buyer` rows (item, pieces, weight, rate; purchasers),
3. `aadat_expense` rows (head, typed or per-piece calculated amount),
4. one `audit_event` row (CREATED).

**Not persisted:** any ledger or journal entry, any farmer or buyer balance, any payable or receivable. Those tables do not exist. Amounts calculated under `business-rules.md` BR-002 to BR-006 are stored with the bill; older bills keep typed amounts.

Integrity that already holds: the whole save is atomic (bill, lots, expenses and audit commit or roll back together; tested, including a simulated audit failure and a mutation check); the database refuses cross-firm references (composite foreign keys); history cannot be altered.

## 2. Proposed model (to build once the posting rules are confirmed)
All tables carry `firm_id` with composite foreign keys, like the existing ones.

| Table | Purpose | Key rules |
|---|---|---|
| `ledger_account` | One account per farmer, per buyer, plus firm accounts the rules require (e.g. commission, expenses) | `(firm_id, owner_type, owner_id)` unique; type (asset/liability/income/expense) from the confirmed chart |
| `journal_entry` | One entry per posting event | `source_type`, `source_id`, `source_version`, `kind` (POST / REVERSAL), `reverses_entry_id`; **unique `(firm_id, source_type, source_id, source_version, kind)` = no duplicate postings** |
| `journal_line` | Debit or credit lines | `debit >= 0`, `credit >= 0`, exactly one of them non-zero; `numeric` with the confirmed precision |

Invariants enforced **in the database**, not only in code:
- **Balanced:** a deferred constraint trigger requires `sum(debit) = sum(credit)` per entry at commit.
- **Append-only:** same protection as `audit_event` (no UPDATE/DELETE/TRUNCATE); corrections are new entries.
- **Same-firm:** composite foreign keys from lines to accounts and entries.
- **Balances are derived** (a view or query summing lines), not a mutable column that can drift; a materialized or cached balance may be added later and must be reconcilable to the lines.

## 3. Posting and transaction integrity
- A posting service creates the journal entry **inside the same transaction as the bill save**, so a bill and its postings can never be partly saved.
- **Only verified amounts can post:** the service accepts a `VerifiedBillTotals` object that only a *verified* rule set can produce. The demo rule set and `NOT_CALCULATED` cannot construct it, so unverified figures can never reach the ledger (compile-time guard plus tests).
- **Idempotency:** the unique key above makes a retried request a no-op instead of a duplicate posting.

## 4. Edit, cancel and reversal (define before enabling either)
- **Never update in place.** An edit = a `REVERSAL` entry that exactly negates the previous posting + a new `POST` entry with `source_version + 1`; the bill keeps a version history.
- **Cancel** = a `REVERSAL` entry + status `CANCELLED`; the number is not reused (if numbering is decided that way).
- Every step writes audit events (`EDITED`, `CANCELLED`, with reason and the entry ids).
- Who may edit or cancel, and until when, is an open decision (BR-105); the permission check arrives with login.

## 5. Implementation plan (in order, after confirmation)
1. Confirm the posting rules with worked examples → business-rule register.
2. Verified rule set + test vectors (components and totals) in `AadatCalculationService`.
3. Migration V5: the three tables, triggers, grants; rollback script with the same discipline as V4 (tested).
4. Posting service + `VerifiedBillTotals` type guard; transactional integration.
5. Read-only account statement for a farmer/buyer (balance = sum of lines).
6. Edit, cancel and numbering policy on top of reversals.

## 6. Tests required (none exist yet because nothing is built)
Every journal entry balances (property test over random bills); posting twice creates one entry; failure during posting rolls back the bill; demo/not-calculated can never post; reversal returns every balance to its prior value; cross-firm posting rejected by the database; ledger tables append-only; balances equal the sum of lines; Hindi/English account statement; **worked examples asserted against owner-approved numbers**.

## 7. Gate to start building
The posting rules answered with worked examples **and** a verified rule set merged. Until then no ledger exists and no payable or receivable is produced.
