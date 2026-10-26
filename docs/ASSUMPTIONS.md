# Assumptions Register

Assumptions are not decisions. Each is isolated so it can be changed cheaply. None is a verified accounting rule (see `business-rules.md`).

| ID | Assumption | Where it lives | If wrong, change |
|---|---|---|---|
| A1 | A **buyer is recorded per lot**; since V11 the purchasers of a lot are rows of what each purchaser bought. The single lot `buyer_id` is kept only for older bills. | `aadat_lot_buyer`, `aadat_lot.buyer_id` | Lot tables and bill form |
| A2 | Vehicle number is free text (max 30), optional. | `aadat_bill.vehicle_no` | One column |
| A3 | A farmer belongs to a village; the bill picks the village first (V12). Older farmers may have none. | `farmer.village_id` | Nullable foreign key |
| A4 | Pieces, weight, rate and typed amounts are limited to 9 integer + 4 fraction digits so they survive the browser's number type unchanged; longer input is rejected, never rounded. A technical tolerance, not a business rule. | validators, `numeric(18,6)` columns | Migration and validators |
| A5 | Expenses come from an expense-head master; an expense head may carry a per-piece rate (BR-005), otherwise the amount is typed. | `ExpenseHead`, `aadat_expense` | Head attributes |
| A6 | Saved bills are read-only (view and print) until the edit and cancel rule is decided (BR-105). Master changes are allowed and audited. | no bill update or delete endpoint | Add endpoint (audit already in place) |
| A7 | The printed bill is a draft layout (BR-107). | `BillPrint` | Template only |
| A8 | The demonstration calculation (`DEMO_UNVERIFIED`, weight x rate) exists to exercise the calculator seam in development. It is off unless enabled, is not offered by the bill form and is refused outside dev/test. | `AadatCalculationService` | Remove when no longer needed |
| A9 | Starter items are onion, potato and garlic with Hindi names; the Hindi wording was written with AI help and needs a Hindi-speaking reviewer. | `V2__initial_items.sql` | Data only |
| A10 | Audit details contain entered names and counts. With real data the audit table becomes business data and must be protected, backed up and retained like the rest. Retention is permanent (owner decision, 2026-10-19). | `audit_event.details` | Review before real use |
| A11 | The firm of a request comes from the signed-in session and an ACTIVE membership; there is no configured single firm. Starter items exist per firm. | `RequestFirmContext`, `FirmContextFilter` | Starter data for new firms |
| A12 | Authentication (email and password, server-side sessions), roles `OWNER`/`EMPLOYEE` and a separate platform administrator who manages owner accounts only are approved and implemented; see `AUTHENTICATION_SECURITY_DECISIONS.md`. | `com.mandi.security`, `com.mandi.identity` | n/a |
