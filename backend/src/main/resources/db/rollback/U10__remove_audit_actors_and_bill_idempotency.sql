-- ROLLBACK for V10. NOT run automatically. Order: U10, U9, U8, ...
-- DESTRUCTIVE for the new columns only: who-did-it attribution of events written after V10 and the idempotency keys of bills
-- are lost (audit rows and bills themselves stay). The script refuses while any event carries an actor or any bill an
-- idempotency key, so attribution is never dropped silently: archive it deliberately first.
do $$
declare a bigint; b bigint;
begin
    select count(*) into a from audit_event where actor_user_id is not null or actor_membership_id is not null or request_id is not null;
    select count(*) into b from aadat_bill where idempotency_key is not null;
    if a > 0 or b > 0 then
        raise exception 'U10 refused: % audit events carry an actor and % bills carry an idempotency key. Back them up first.', a, b;
    end if;
end $$;
drop index aadat_bill_idempotency_uk;
alter table aadat_bill drop column request_hash;
alter table aadat_bill drop column idempotency_key;
drop index idx_audit_event_actor;
alter table audit_event drop column request_id;
alter table audit_event drop column actor_role;
alter table audit_event drop column actor_membership_id;
alter table audit_event drop column actor_user_id;
delete from flyway_schema_history where version = '10';
