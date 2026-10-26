-- Additive indexes for the query shapes the application really runs (docs/DATABASE_ARCHITECTURE_ASSESSMENT.md
-- section 3.1, measurements in docs/DB_INDEXES_AND_QUERIES.md). Adds indexes only: no table, column, constraint or
-- row changes, no effect on any business rule.
--
-- CONCURRENTLY: builds do not block writes. It cannot run inside a transaction, so this migration is configured
-- with executeInTransaction=false (V5__additive_indexes.sql.conf next to this file). Each statement therefore
-- commits on its own; every statement is IF NOT EXISTS so a re-run after a failure continues where it stopped.
-- A failed CONCURRENTLY build leaves an INVALID index that IF NOT EXISTS would silently accept, so the last
-- statement fails the migration loudly if any index is invalid (drop it by hand, then run the migration again).
-- Rollback: db/rollback/U5__drop_additive_indexes.sql.
--
-- Existing single-column indexes are deliberately NOT dropped here. After these composites exist,
-- idx_aadat_bill_firm, idx_aadat_bill_date and the idx_<master>_firm indexes are candidates for removal, to be
-- decided from production statistics (pg_stat_user_indexes), not from this migration.

-- Bill list: WHERE firm_id = ? [AND bill_date range] ORDER BY bill_date DESC, id DESC
create index concurrently if not exists idx_aadat_bill_firm_date
    on aadat_bill (firm_id, bill_date desc, id desc);
-- Bill list filtered by farmer
create index concurrently if not exists idx_aadat_bill_firm_farmer_date
    on aadat_bill (firm_id, farmer_id, bill_date desc, id desc);

-- Foreign-key support. The composite foreign keys are (x_id, firm_id); a lookup by the referencing columns is what
-- a parent-row change or a "which bills use this item/buyer/head/village" query performs.
create index concurrently if not exists idx_aadat_bill_village
    on aadat_bill (village_id, firm_id) where village_id is not null;
create index concurrently if not exists idx_aadat_lot_item
    on aadat_lot (item_id, firm_id);
create index concurrently if not exists idx_aadat_lot_buyer
    on aadat_lot (buyer_id, firm_id) where buyer_id is not null;
create index concurrently if not exists idx_aadat_expense_head
    on aadat_expense (expense_head_id, firm_id);

-- Master lists: WHERE firm_id = ? [AND active] ORDER BY name, id (the order used by list and by paging).
-- Substring search (lower(name) LIKE '%q%') is NOT indexed here: see docs/DB_INDEXES_AND_QUERIES.md for why.
create index concurrently if not exists idx_farmer_firm_name       on farmer (firm_id, name, id);
create index concurrently if not exists idx_buyer_firm_name        on buyer (firm_id, name, id);
create index concurrently if not exists idx_village_firm_name      on village (firm_id, name, id);
create index concurrently if not exists idx_item_firm_name         on item (firm_id, name, id);
create index concurrently if not exists idx_expense_head_firm_name on expense_head (firm_id, name, id);

do $$
declare
    bad text;
begin
    select string_agg(c.relname, ', ') into bad
    from pg_index i join pg_class c on c.oid = i.indexrelid
    where c.relnamespace = 'public'::regnamespace and (not i.indisvalid or not i.indisready);
    if bad is not null then
        raise exception 'invalid index left behind by a failed concurrent build: %. Drop it and run the migration again.', bad;
    end if;
end $$;
