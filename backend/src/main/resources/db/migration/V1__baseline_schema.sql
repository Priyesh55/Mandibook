-- Masters: minimal, bilingual. No business attributes beyond what the family has confirmed.
create table village (
    id bigint generated always as identity primary key,
    name varchar(200) not null,
    name_hi varchar(200),
    active boolean not null default true,
    created_at timestamptz not null default now()
);
create table farmer (like village including all);
create table buyer (like village including all);
create table item (like village including all);
create table expense_head (like village including all);

-- Aadat bill. Rule-set and status are recorded per bill. No computed monetary amounts are stored:
-- calculation rules are unverified (see docs/LEGACY_BEHAVIOR_FINDINGS.md).
create table aadat_bill (
    id bigint generated always as identity primary key,
    bill_date date not null,
    farmer_id bigint not null references farmer(id),
    village_id bigint references village(id),
    vehicle_no varchar(30),
    calculation_status varchar(30) not null,
    rule_set_id varchar(60) not null,
    created_at timestamptz not null default now()
);
create index idx_aadat_bill_date on aadat_bill(bill_date);
create index idx_aadat_bill_farmer on aadat_bill(farmer_id);

-- Lot-wise goods. Buyer is recorded per lot as an UNCONFIRMED assumption (docs/ASSUMPTIONS.md A1).
-- Values are stored exactly as entered (no rounding, no unit conversion).
create table aadat_lot (
    id bigint generated always as identity primary key,
    bill_id bigint not null references aadat_bill(id) on delete cascade,
    line_no integer not null,
    item_id bigint not null references item(id),
    pieces integer check (pieces >= 0),
    weight numeric(18,6) check (weight >= 0),
    rate numeric(18,6) check (rate >= 0),
    buyer_id bigint references buyer(id),
    unique (bill_id, line_no)
);

-- Manually entered expense amounts (recorded values, not computed).
create table aadat_expense (
    id bigint generated always as identity primary key,
    bill_id bigint not null references aadat_bill(id) on delete cascade,
    seq integer not null,
    expense_head_id bigint not null references expense_head(id),
    amount numeric(18,6) not null check (amount >= 0),
    unique (bill_id, seq)
);
