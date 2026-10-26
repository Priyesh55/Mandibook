-- ROLLBACK for V12. NOT run automatically. Order: U12, U11, U10, ...
-- Refuses while any farmer has a village, so the association is never dropped silently: archive it deliberately first.
do $$
declare n bigint;
begin
    select count(*) into n from farmer where village_id is not null;
    if n > 0 then
        raise exception 'U12 refused: % farmers have a village. Back them up first.', n;
    end if;
end $$;
drop index idx_farmer_village;
alter table farmer drop constraint farmer_village_firm_fk;
alter table farmer drop column village_id;
delete from flyway_schema_history where version = '12';
