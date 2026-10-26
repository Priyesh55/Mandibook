-- ROLLBACK for V7__platform_boundary.sql. NOT run automatically (Flyway does not scan db/rollback).
-- Order of rollbacks is the reverse of migrations: U7 first, then U6.
-- Drops the two contract views and gives PostgreSQL's DEFAULT access back to PUBLIC (usage on schema public and execute
-- on functions). That default is the hazard V7 closes: re-open it only deliberately, and not while the platform role exists.
drop view platform.owner_user_summary;
drop view public.account_access;
grant usage on schema public to public;
grant execute on all functions in schema public to public;
alter default privileges grant execute on functions to public;
delete from flyway_schema_history where version = '7';
-- both repeatable grant scripts mention objects V7 created: forget them so they are applied again with V7
delete from flyway_schema_history where script in ('R__runtime_grants.sql', 'R__platform_grants.sql');
