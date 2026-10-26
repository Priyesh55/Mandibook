-- V11: Aadat bill workflow (header fields, bill number, financial year, lot value and marka, purchaser allocations,
-- expense head order, firm print profile). ADDITIVE ONLY: every new column on an existing table is nullable or generated, so
-- existing rows stay valid and unchanged. Nothing here is a financial rule: amounts are values TYPED by the operator and stored
-- exactly as entered; no calculation, rounding or unit is introduced.
-- Rollback: db/rollback/U11__remove_aadat_workflow.sql (refuses while any new data exists). Tested on disposable databases.

-- ---- firm print profile (optional text shown on the printed bill) -------------------------------------------------------
alter table firm add column address     varchar(300);
alter table firm add column address_hi  varchar(300);
alter table firm add column phone       varchar(30);
alter table firm add column licence_no  varchar(60);
alter table firm add column shop_no     varchar(30);

-- ---- bill header --------------------------------------------------------------------------------------------------------
-- financial year: 1 April to 31 March. GENERATED from the bill date so it can never disagree with it and needs no backfill
-- or default. fy_start_year = 2026 means 2026-27.
alter table aadat_bill add column fy_start_year integer generated always as (
    case when extract(month from bill_date) >= 4 then extract(year from bill_date)::integer
         else extract(year from bill_date)::integer - 1 end) stored;
-- official bill number: one continuous series per firm across financial years; NULL for bills saved before V11
alter table aadat_bill add column bill_no integer;
alter table aadat_bill add constraint aadat_bill_no_positive check (bill_no is null or bill_no > 0);
alter table aadat_bill add column transport    varchar(100);
alter table aadat_bill add column bhada_paid   boolean;
alter table aadat_bill add column bhada_amount numeric(18,6);
alter table aadat_bill add constraint aadat_bill_bhada_amount_ck check (bhada_amount is null or bhada_amount >= 0);
alter table aadat_bill add constraint aadat_bill_bhada_paid_ck   check (bhada_amount is null or coalesce(bhada_paid, false));
-- label only, no accounting effect (meaning of cash bills is unconfirmed)
alter table aadat_bill add column bill_type varchar(10);
alter table aadat_bill add constraint aadat_bill_type_ck check (bill_type is null or bill_type in ('CREDIT', 'CASH'));
-- optional reference numbers: meaning NOT confirmed, stored as typed, never required
alter table aadat_bill add column panjiyan_no varchar(40);
alter table aadat_bill add column book_no     varchar(30);
alter table aadat_bill add column receipt_no  varchar(30);
alter table aadat_bill add column entry_no    varchar(30);
alter table aadat_bill add column ws_no       varchar(30);

create unique index aadat_bill_no_uk on aadat_bill (firm_id, bill_no) where bill_no is not null;
create index idx_aadat_bill_fy on aadat_bill (firm_id, fy_start_year, bill_date);

-- ---- bill number counter: one row per firm, locked while a bill is saved ---------------------------------------------------
create table bill_number_counter (
    firm_id bigint primary key references firm (id),
    next_no integer not null check (next_no >= 1)
);

-- ---- lots ----------------------------------------------------------------------------------------------------------------
alter table aadat_lot add column marka varchar(60);
-- the farmer-side amount of the lot as TYPED by the operator (not computed)
alter table aadat_lot add column lot_value numeric(18,6);
alter table aadat_lot add constraint aadat_lot_value_ck check (lot_value is null or lot_value >= 0);
-- target of the composite key of the purchaser rows: a row can only point at a lot OF THE SAME BILL
alter table aadat_lot add constraint aadat_lot_id_bill_uk unique (id, bill_id);

-- ---- purchaser allocations: the ACTUAL quantities bought by each purchaser of a lot, as typed ---------------------------------
create table aadat_lot_buyer (
    id bigint generated always as identity primary key,
    firm_id bigint not null,
    bill_id bigint not null,
    lot_id bigint not null,
    seq integer not null,
    buyer_id bigint not null,
    haste varchar(100),
    pieces integer check (pieces >= 0),
    weight numeric(18,6) check (weight >= 0),
    rate numeric(18,6) check (rate >= 0),
    value numeric(18,6) check (value >= 0),
    commission_applies boolean not null default false,
    commission_percent numeric(9,4) check (commission_percent >= 0 and commission_percent <= 100),
    commission_amount numeric(18,6) check (commission_amount >= 0),
    constraint aadat_lot_buyer_seq_uk unique (lot_id, seq),
    constraint aadat_lot_buyer_commission_ck check (commission_applies or (commission_percent is null and commission_amount is null)),
    -- the lot must belong to this bill, and the bill to this firm: a row can reach neither another bill nor another firm
    constraint aadat_lot_buyer_lot_fk   foreign key (lot_id, bill_id)  references aadat_lot (id, bill_id),
    constraint aadat_lot_buyer_bill_fk  foreign key (bill_id, firm_id) references aadat_bill (id, firm_id),
    constraint aadat_lot_buyer_buyer_fk foreign key (buyer_id, firm_id) references buyer (id, firm_id)
);
create index idx_aadat_lot_buyer_bill  on aadat_lot_buyer (bill_id);
create index idx_aadat_lot_buyer_buyer on aadat_lot_buyer (buyer_id);

-- ---- expense head display order (NULL sorts last, then by name) ----------------------------------------------------------------
alter table expense_head add column sort_order integer;
