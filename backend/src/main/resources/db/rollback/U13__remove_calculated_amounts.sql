-- ROLLBACK for V13. NOT run automatically. Order: U13, U12, U11, ...
-- Refuses while any expense head has a predefined rate or any expense line was calculated, so nothing is dropped silently.
do $$
declare heads bigint; lines bigint;
begin
    select count(*) into heads from expense_head where rate_basis is not null;
    select count(*) into lines from aadat_expense where quantity is not null;
    if heads > 0 or lines > 0 then
        raise exception 'U13 refused: % expense heads have a rate and % expense lines were calculated. Back them up first.', heads, lines;
    end if;
end $$;
alter table aadat_expense drop constraint aadat_expense_calc_ck;
alter table aadat_expense drop column rate;
alter table aadat_expense drop column quantity;
alter table expense_head drop constraint expense_head_rate_ck;
alter table expense_head drop constraint expense_head_rate_basis_ck;
alter table expense_head drop column rate;
alter table expense_head drop column rate_basis;
delete from flyway_schema_history where version = '13';
