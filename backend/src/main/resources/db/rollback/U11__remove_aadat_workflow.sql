-- ROLLBACK for V11. NOT run automatically. Order: U11, U10, U9, ...
-- DESTRUCTIVE for the new columns and tables only. The script REFUSES while any of them holds data (purchaser rows, bill
-- numbers, header fields, lot values or markas, firm print profile values, a number counter, expense head order), so entered
-- information is never dropped silently: archive it deliberately first. Bills, lots, expenses and audit rows themselves stay.
do $$
declare allocs bigint; numbered bigint; headers bigint; lots bigint; profiles bigint; counters bigint; heads bigint;
begin
    select count(*) into allocs from aadat_lot_buyer;
    select count(*) into numbered from aadat_bill where bill_no is not null;
    select count(*) into headers from aadat_bill where transport is not null or bhada_paid is not null or bhada_amount is not null
        or bill_type is not null or panjiyan_no is not null or book_no is not null or receipt_no is not null
        or entry_no is not null or ws_no is not null;
    select count(*) into lots from aadat_lot where marka is not null or lot_value is not null;
    select count(*) into profiles from firm where address is not null or address_hi is not null or phone is not null
        or licence_no is not null or shop_no is not null;
    select count(*) into counters from bill_number_counter;
    select count(*) into heads from expense_head where sort_order is not null;
    if allocs + numbered + headers + lots + profiles + counters + heads > 0 then
        raise exception 'U11 refused: % purchaser rows, % numbered bills, % bills with header fields, % lots with marka or value, % firm profiles, % number counters, % ordered expense heads. Back them up first.',
            allocs, numbered, headers, lots, profiles, counters, heads;
    end if;
end $$;
alter table expense_head drop column sort_order;
drop table aadat_lot_buyer;
alter table aadat_lot drop constraint aadat_lot_id_bill_uk;
alter table aadat_lot drop column lot_value;
alter table aadat_lot drop column marka;
drop table bill_number_counter;
drop index idx_aadat_bill_fy;
drop index aadat_bill_no_uk;
alter table aadat_bill drop column ws_no;
alter table aadat_bill drop column entry_no;
alter table aadat_bill drop column receipt_no;
alter table aadat_bill drop column book_no;
alter table aadat_bill drop column panjiyan_no;
alter table aadat_bill drop column bill_type;
alter table aadat_bill drop column bhada_amount;
alter table aadat_bill drop column bhada_paid;
alter table aadat_bill drop column transport;
alter table aadat_bill drop column bill_no;
alter table aadat_bill drop column fy_start_year;
alter table firm drop column shop_no;
alter table firm drop column licence_no;
alter table firm drop column phone;
alter table firm drop column address_hi;
alter table firm drop column address;
delete from flyway_schema_history where version = '11';
