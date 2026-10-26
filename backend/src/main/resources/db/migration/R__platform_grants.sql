-- Privileges of the PLATFORM role `mandi_platform` (docs/AUTHENTICATION_SECURITY_DECISIONS.md section 4.2).
-- Runs as the owner/migrator connection. Repeatable: re-applied whenever this file changes.
--
-- Fail-closed by design, like R__runtime_grants.sql: everything is reset to NOTHING first, and only the lines below are
-- granted. A table or view added later gets NO platform access until it is listed here and in
-- PlatformBoundaryIntegrationTest. The platform role gets NOTHING in schema public: no USAGE, so no business table, no
-- business view, no function, whatever a later migration adds there.
-- The role itself (login, password, no inherited roles) is created by scripts/db/setup-platform-role.sh, because a password
-- must never live in a migration. If the role does not exist this file does nothing.
do $$
declare
    wanted constant text[][] := array[
        -- relation,                  privileges
        array['platform_admin',       'select, update'],      -- sign-in bookkeeping; first admin is created out of band by the DB owner
        array['owner_account',        'select, insert, update'],
        array['owner_invitation',     'select, insert, update'],
        array['owner_user_summary',   'select']               -- view: owner users only, no secrets
    ];
    i int;
begin
    if not exists (select 1 from pg_roles where rolname = 'mandi_platform') then
        return;
    end if;
    -- older schema versions (before V6) have no schema platform yet: nothing to grant
    if to_regnamespace('platform') is null then
        return;
    end if;
    -- business world: nothing at all
    execute 'revoke all on all tables in schema public from mandi_platform';
    execute 'revoke all on all sequences in schema public from mandi_platform';
    execute 'revoke all on all functions in schema public from mandi_platform';
    execute 'revoke all on schema public from mandi_platform';
    -- platform world: reset, then the list
    execute 'revoke all on all tables in schema platform from mandi_platform';
    execute 'revoke all on all sequences in schema platform from mandi_platform';
    execute 'revoke all on all functions in schema platform from mandi_platform';
    execute 'revoke all on schema platform from mandi_platform';
    execute 'grant usage on schema platform to mandi_platform';
    for i in 1 .. array_length(wanted, 1) loop
        if to_regclass('platform.' || wanted[i][1]) is not null then
            execute format('grant %s on platform.%I to mandi_platform', wanted[i][2], wanted[i][1]);
        end if;
    end loop;
end $$;
