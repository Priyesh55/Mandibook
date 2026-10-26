-- ROLLBACK for V9__owner_invitation_acceptance.sql. NOT run automatically. Order: U9, U8, U7, U6.
-- Only removes the two functions. Invitations and accounts already accepted stay as they are.
drop function public.accept_owner_invitation(bytea, bigint);
drop function public.peek_owner_invitation(bytea);
delete from flyway_schema_history where version = '9';
delete from flyway_schema_history where script = 'R__runtime_grants.sql';
