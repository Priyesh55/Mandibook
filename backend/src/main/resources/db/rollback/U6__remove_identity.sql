-- ROLLBACK for V6__identity_core.sql. NOT run automatically (Flyway does not scan db/rollback).
-- Drops the identity tables and the schema `platform`, and forgets that V6 was applied so it can be applied again.
-- Order of rollbacks is the reverse of migrations: U6 first, then U5, then U4 (anything added after V6 must be rolled
-- back before this script).
--
-- SAFETY: identity rows are real accounts once the application is used. This script REFUSES to run when any identity row
-- exists; remove synthetic rows deliberately first (the delete triggers protect users and memberships, so a person
-- has to disable them on purpose: that is the intended friction), or restore from a backup instead.
do $$
declare
    n bigint;
begin
    select (select count(*) from app_user) + (select count(*) from firm_member) + (select count(*) from credential_token)
         + (select count(*) from firm_invitation) + (select count(*) from platform.platform_admin)
         + (select count(*) from platform.owner_account) + (select count(*) from platform.owner_invitation)
      into n;
    if n > 0 then
        raise exception 'U6 refused: % identity rows exist. Back up and remove them deliberately first.', n;
    end if;
end $$;

drop table platform.owner_invitation;
drop table platform.owner_account;
drop table platform.platform_admin;
drop schema platform;

drop table firm_invitation;
drop table credential_token;
drop table firm_member;     -- also drops its triggers
drop table app_user;
drop function firm_member_keys_are_immutable();
drop function identity_rows_are_not_deleted();

delete from flyway_schema_history where version = '6';
-- The runtime grants (repeatable) mention the identity tables; forget that they were applied so that they are applied again,
-- together with V6, if V6 is re-applied. Without the identity tables the grants script simply skips those lines.
delete from flyway_schema_history where script = 'R__runtime_grants.sql';
