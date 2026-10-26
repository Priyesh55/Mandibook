-- V13: amounts on a bill are CALCULATED by the server from the owner-confirmed rules (2026-10-25), no longer typed.
--  * an expense head may carry a predefined rate per piece (bag); the bill multiplies the number of pieces by it
--  * a saved expense line remembers the quantity and rate it was calculated with, so a later change of the master rate never
--    alters an old bill
-- ADDITIVE: every column is nullable; bills saved before this version keep their typed amounts and the status NOT_CALCULATED.
-- Rollback: db/rollback/U13__remove_calculated_amounts.sql.
alter table expense_head add column rate_basis varchar(12);
alter table expense_head add column rate numeric(18,6);
alter table expense_head add constraint expense_head_rate_basis_ck check (rate_basis is null or rate_basis = 'PER_PIECE');
alter table expense_head add constraint expense_head_rate_ck check ((rate_basis is null and rate is null) or (rate_basis is not null and rate is not null and rate >= 0));

alter table aadat_expense add column quantity numeric(18,6);
alter table aadat_expense add column rate numeric(18,6);
alter table aadat_expense add constraint aadat_expense_calc_ck check ((quantity is null) = (rate is null) and (quantity is null or (quantity >= 0 and rate >= 0)));
