-- ROLLBACK for V5__additive_indexes.sql. NOT run automatically (Flyway does not scan db/rollback).
-- Removes only the indexes V5 added and forgets that V5 was applied, so it can be applied again.
-- Order of rollbacks is the reverse of migrations: U5 first, then U4.
-- On a large live table prefer running each statement as DROP INDEX CONCURRENTLY by hand (it cannot run inside the
-- implicit transaction used to run this whole file); the plain form below takes a brief exclusive lock.
drop index if exists idx_aadat_bill_firm_date;
drop index if exists idx_aadat_bill_firm_farmer_date;
drop index if exists idx_aadat_bill_village;
drop index if exists idx_aadat_lot_item;
drop index if exists idx_aadat_lot_buyer;
drop index if exists idx_aadat_expense_head;
drop index if exists idx_farmer_firm_name;
drop index if exists idx_buyer_firm_name;
drop index if exists idx_village_firm_name;
drop index if exists idx_item_firm_name;
drop index if exists idx_expense_head_firm_name;
delete from flyway_schema_history where version = '5';
