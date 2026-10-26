-- Firm ownership boundary: one firm owns its business data; other firms can never see or reference it.
-- Existing rows are backfilled to a single DEFAULT firm. Rollback: db/rollback/U4__remove_firm.sql (tested).

create table firm (
    id bigint generated always as identity primary key,
    code varchar(30) not null unique,
    name varchar(200) not null,
    name_hi varchar(200),
    active boolean not null default true,
    created_at timestamptz not null default now()
);

insert into firm (code, name) values ('DEFAULT', 'Default firm');

-- Add firm_id to every business table and to the audit history.
-- ADD COLUMN ... DEFAULT fills existing rows without running UPDATE, so the append-only trigger on
-- audit_event is neither bypassed nor disabled. The default is dropped afterwards: new rows must name their firm.
do $$
declare
    fid bigint;
    t text;
begin
    select id into fid from firm where code = 'DEFAULT';
    foreach t in array array['farmer', 'buyer', 'village', 'item', 'expense_head',
                             'aadat_bill', 'aadat_lot', 'aadat_expense', 'audit_event'] loop
        execute format('alter table %I add column firm_id bigint not null default %s', t, fid);
        execute format('alter table %I alter column firm_id drop default', t);
        execute format('alter table %I add constraint %I foreign key (firm_id) references firm (id)', t, t || '_firm_id_fkey');
        execute format('create index %I on %I (firm_id)', 'idx_' || t || '_firm', t);
    end loop;
end $$;

-- The database itself refuses cross-firm references: every relation carries the firm and must match it.
alter table farmer       add constraint farmer_id_firm_uk       unique (id, firm_id);
alter table buyer        add constraint buyer_id_firm_uk        unique (id, firm_id);
alter table village      add constraint village_id_firm_uk      unique (id, firm_id);
alter table item         add constraint item_id_firm_uk         unique (id, firm_id);
alter table expense_head add constraint expense_head_id_firm_uk unique (id, firm_id);
alter table aadat_bill   add constraint aadat_bill_id_firm_uk   unique (id, firm_id);

alter table aadat_bill drop constraint aadat_bill_farmer_id_fkey;
alter table aadat_bill drop constraint aadat_bill_village_id_fkey;
alter table aadat_bill add constraint aadat_bill_farmer_firm_fk
    foreign key (farmer_id, firm_id) references farmer (id, firm_id);
alter table aadat_bill add constraint aadat_bill_village_firm_fk
    foreign key (village_id, firm_id) references village (id, firm_id);

alter table aadat_lot drop constraint aadat_lot_bill_id_fkey;
alter table aadat_lot drop constraint aadat_lot_item_id_fkey;
alter table aadat_lot drop constraint aadat_lot_buyer_id_fkey;
alter table aadat_lot add constraint aadat_lot_bill_firm_fk
    foreign key (bill_id, firm_id) references aadat_bill (id, firm_id) on delete cascade;
alter table aadat_lot add constraint aadat_lot_item_firm_fk
    foreign key (item_id, firm_id) references item (id, firm_id);
alter table aadat_lot add constraint aadat_lot_buyer_firm_fk
    foreign key (buyer_id, firm_id) references buyer (id, firm_id);

alter table aadat_expense drop constraint aadat_expense_bill_id_fkey;
alter table aadat_expense drop constraint aadat_expense_expense_head_id_fkey;
alter table aadat_expense add constraint aadat_expense_bill_firm_fk
    foreign key (bill_id, firm_id) references aadat_bill (id, firm_id) on delete cascade;
alter table aadat_expense add constraint aadat_expense_head_firm_fk
    foreign key (expense_head_id, firm_id) references expense_head (id, firm_id);
