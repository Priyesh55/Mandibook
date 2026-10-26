-- Privileges of the restricted RUNTIME role `mandi_app` (docs/DB_BACKUP_AND_ROLES.md).
-- Runs as the owner/migrator connection. Repeatable: re-applied whenever this file changes.
--
-- Fail-closed by design: privileges are reset to NOTHING first and then only the lines below are granted.
-- A table added by a later migration gets NO runtime access until it is listed here, and
-- RuntimePrivilegesIntegrationTest fails until the list and the test agree.
--
-- The runtime role gets: no DELETE anywhere (the API never deletes), no UPDATE on bills, lots and expenses
-- (saved bills are read-only), and ONLY SELECT + INSERT on audit_event (history is append-only: the role cannot
-- UPDATE, DELETE or TRUNCATE it, and cannot disable its triggers because it does not own the table).
-- Identity columns need no separate sequence privilege: INSERT on the table is enough.
-- The role itself (login, password, no superuser/createdb/createrole/bypassrls) is created by
-- scripts/db/setup-runtime-role.sh, because a password must never live in a migration.
-- If the role does not exist (tests of old schema versions, fresh environments) this file does nothing.
do $$
declare
    wanted constant text[][] := array[
        -- table,          privileges
        -- INSERT on firm: an owner creates a firm through the application (OnboardingService.createFirm); never UPDATE or DELETE
        array['firm',          'select, insert'],
        array['farmer',        'select, insert, update'],
        array['buyer',         'select, insert, update'],
        array['village',       'select, insert, update'],
        array['item',          'select, insert, update'],
        array['expense_head',  'select, insert, update'],
        array['aadat_bill',    'select, insert'],
        array['aadat_lot',     'select, insert'],
        array['aadat_expense', 'select, insert'],
        -- V11: purchaser rows are read-only after save like lots; the counter is the only business table that is UPDATEd
        -- (a number counter, never a financial record; never DELETEd)
        array['aadat_lot_buyer',    'select, insert'],
        array['bill_number_counter', 'select, insert, update'],
        array['audit_event',   'select, insert'],
        -- identity (V6): never DELETE (triggers refuse it for everyone as well); tokens are consumed by UPDATE
        array['app_user',         'select, insert, update'],
        array['firm_member',      'select, insert, update'],
        array['credential_token', 'select, insert, update'],
        array['firm_invitation',  'select, insert, update'],
        -- contract surface (V7): commercial state of an owner's account, no personal data (a view, read-only)
        array['account_access',   'select'],
        -- authentication (V8). Sessions are the ONLY place the runtime role may DELETE: the session store removes
        -- expired and logged-out sessions (and cleans up the throttle counters). The security event log is append-only.
        array['spring_session',            'select, insert, update, delete'],
        array['spring_session_attributes', 'select, insert, update, delete'],
        array['auth_throttle',             'select, insert, update, delete'],
        array['auth_event',                'select, insert']
    ];
    i int;
begin
    if not exists (select 1 from pg_roles where rolname = 'mandi_app') then
        return;
    end if;
    execute 'revoke all on all tables in schema public from mandi_app';
    execute 'revoke all on all sequences in schema public from mandi_app';
    execute 'revoke all on all functions in schema public from mandi_app';
    -- Schema platform (platform administrators, owner accounts) is a different world: the runtime role gets nothing there,
    -- not even USAGE. Reset explicitly so that no earlier grant can survive; the platform role has its own grants.
    if exists (select 1 from pg_namespace where nspname = 'platform') then
        execute 'revoke all on all tables in schema platform from mandi_app';
        execute 'revoke all on all sequences in schema platform from mandi_app';
        execute 'revoke all on all functions in schema platform from mandi_app';
        execute 'revoke all on schema platform from mandi_app';
    end if;
    execute 'grant usage on schema public to mandi_app';
    -- The ONLY functions the runtime role may execute (V9): the narrow door to the platform schema for accepting an
    -- owner invitation. Everything else was revoked above; PUBLIC has no execute right (V7).
    if to_regprocedure('public.peek_owner_invitation(bytea)') is not null then
        execute 'grant execute on function public.peek_owner_invitation(bytea) to mandi_app';
        execute 'grant execute on function public.accept_owner_invitation(bytea, bigint) to mandi_app';
    end if;
    -- V11: an owner edits the PRINT PROFILE of the firm. Column-level UPDATE on exactly these columns: code, name, active and
    -- id stay unchangeable for the runtime role.
    if exists (select 1 from information_schema.columns where table_schema = 'public' and table_name = 'firm' and column_name = 'licence_no') then
        execute 'grant update (address, address_hi, phone, licence_no, shop_no) on public.firm to mandi_app';
    end if;
    for i in 1 .. array_length(wanted, 1) loop
        -- a table that does not exist yet (older schema version) is skipped, not an error
        if to_regclass('public.' || wanted[i][1]) is not null then
            execute format('grant %s on public.%I to mandi_app', wanted[i][2], wanted[i][1]);
        end if;
    end loop;
end $$;
