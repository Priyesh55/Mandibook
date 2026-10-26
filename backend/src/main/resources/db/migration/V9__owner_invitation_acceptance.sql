-- Owner invitation acceptance: the narrow, audited door between the business application and the platform schema
-- (decision memo 4.2, blueprint 3.3 "contract surface"). ADDITIVE ONLY. Rollback: db/rollback/U9__remove_owner_invitation_acceptance.sql.
--
-- A platform administrator (or, today in development, the database owner) creates an owner account and an invitation in
-- schema platform; only the SHA-256 digest of the token is stored. The invited person opens the emailed link and sets a
-- password through the business application, which runs as the restricted role `mandi_app`. That role has NO privilege on
-- schema platform, so it can only do exactly two things, through these SECURITY DEFINER functions:
--   1. peek:   is this token an open invitation, and for which email?  (no other column is returned)
--   2. accept: atomically consume the invitation and link the NEW business user to the owner account (starting the
--              trial when the account is on trial). It checks that the user's email is the invited email.
-- Both pin their search_path, are executable by mandi_app only (never by PUBLIC or the platform role), and return nothing
-- about firms or business data. The administrator never sees the token or the password.

create function public.peek_owner_invitation(p_token_hash bytea) returns table (owner_account_id bigint, email varchar)
language sql stable security definer set search_path = pg_catalog, platform, public as $$
    select i.owner_account_id, i.email
      from platform.owner_invitation i
     where i.token_hash = p_token_hash and i.accepted_at is null and i.revoked_at is null and i.expires_at > now()
$$;

create function public.accept_owner_invitation(p_token_hash bytea, p_user_id bigint) returns bigint
language plpgsql security definer set search_path = pg_catalog, platform, public as $$
declare
    v_account bigint;
    v_email   varchar;
    v_user_email varchar;
begin
    select u.email into v_user_email from public.app_user u where u.id = p_user_id;
    if v_user_email is null then
        raise exception 'unknown user' using errcode = 'P0002';
    end if;
    -- one atomic statement: only one caller can ever consume an invitation
    update platform.owner_invitation
       set accepted_at = now()
     where token_hash = p_token_hash and accepted_at is null and revoked_at is null and expires_at > now()
    returning owner_account_id, email into v_account, v_email;
    if v_account is null then
        raise exception 'invitation is not open' using errcode = 'P0002';
    end if;
    if v_email <> v_user_email then
        raise exception 'invitation was issued for another email address' using errcode = 'P0001';
    end if;
    -- link the owner account to the new user; the trial starts now (blueprint RD-8) when the account is on trial
    update platform.owner_account
       set owner_user_id    = p_user_id,
           trial_started_at = case when status = 'TRIAL' then coalesce(trial_started_at, now()) end,
           trial_ends_at    = case when status = 'TRIAL' then coalesce(trial_ends_at, now() + interval '14 days') end
     where id = v_account and owner_user_id is null;
    if not found then
        raise exception 'owner account is already linked' using errcode = 'P0001';
    end if;
    return v_account;
end $$;

revoke all on function public.peek_owner_invitation(bytea) from public;
revoke all on function public.accept_owner_invitation(bytea, bigint) from public;
