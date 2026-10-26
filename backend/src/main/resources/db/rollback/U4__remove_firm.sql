-- ROLLBACK for V4__firm_ownership.sql. NOT run automatically (Flyway does not scan db/rollback).
-- Procedure and safeguards: docs/FIRM_ISOLATION.md. Always take a pg_dump backup first.
-- Restores the exact pre-V4 schema (same constraint names) and keeps all business and audit rows.

do $$
begin
    if (select count(*) from firm) > 1 then
        raise exception 'rollback refused: more than one firm exists; removing the firm boundary would merge their data';
    end if;
end $$;

-- 1. composite foreign keys out, original simple foreign keys back (same names as V1)
alter table aadat_expense drop constraint aadat_expense_head_firm_fk;
alter table aadat_expense drop constraint aadat_expense_bill_firm_fk;
alter table aadat_expense add constraint aadat_expense_bill_id_fkey
    foreign key (bill_id) references aadat_bill (id) on delete cascade;
alter table aadat_expense add constraint aadat_expense_expense_head_id_fkey
    foreign key (expense_head_id) references expense_head (id);

alter table aadat_lot drop constraint aadat_lot_buyer_firm_fk;
alter table aadat_lot drop constraint aadat_lot_item_firm_fk;
alter table aadat_lot drop constraint aadat_lot_bill_firm_fk;
alter table aadat_lot add constraint aadat_lot_bill_id_fkey
    foreign key (bill_id) references aadat_bill (id) on delete cascade;
alter table aadat_lot add constraint aadat_lot_item_id_fkey
    foreign key (item_id) references item (id);
alter table aadat_lot add constraint aadat_lot_buyer_id_fkey
    foreign key (buyer_id) references buyer (id);

alter table aadat_bill drop constraint aadat_bill_village_firm_fk;
alter table aadat_bill drop constraint aadat_bill_farmer_firm_fk;
alter table aadat_bill add constraint aadat_bill_farmer_id_fkey
    foreign key (farmer_id) references farmer (id);
alter table aadat_bill add constraint aadat_bill_village_id_fkey
    foreign key (village_id) references village (id);

-- 2. composite unique keys out
alter table aadat_bill   drop constraint aadat_bill_id_firm_uk;
alter table expense_head drop constraint expense_head_id_firm_uk;
alter table item         drop constraint item_id_firm_uk;
alter table village      drop constraint village_id_firm_uk;
alter table buyer        drop constraint buyer_id_firm_uk;
alter table farmer       drop constraint farmer_id_firm_uk;

-- 3. firm_id columns out (DROP COLUMN does not run row triggers; audit rows are kept untouched)
do $$
declare
    t text;
begin
    foreach t in array array['audit_event', 'aadat_expense', 'aadat_lot', 'aadat_bill', 'expense_head',
                             'item', 'village', 'buyer', 'farmer'] loop
        execute format('drop index %I', 'idx_' || t || '_firm');
        execute format('alter table %I drop constraint %I', t, t || '_firm_id_fkey');
        execute format('alter table %I drop column firm_id', t);
    end loop;
end $$;

-- 4. firm table out, and forget that V4 was applied so it can be applied again later
drop table firm;
delete from flyway_schema_history where version = '4';
