-- ROLLBACK for V8__authentication.sql. NOT run automatically (Flyway does not scan db/rollback).
-- Order of rollbacks is the reverse of migrations: U8 first, then U7, U6.
-- Sessions and throttle counters are disposable (everybody is signed out). The security event log is NOT: this script
-- REFUSES to run while auth_event has rows; archive or back it up and remove it deliberately first (the append-only
-- triggers stop a plain DELETE, which is the intended friction).
do $$
declare n bigint;
begin
    select count(*) into n from auth_event;
    if n > 0 then
        raise exception 'U8 refused: auth_event holds % rows (append-only security history). Back it up first.', n;
    end if;
end $$;
drop table auth_throttle;
drop table auth_event;
drop function auth_event_is_append_only();
drop table spring_session_attributes;
drop table spring_session;
delete from flyway_schema_history where version = '8';
delete from flyway_schema_history where script = 'R__runtime_grants.sql';
