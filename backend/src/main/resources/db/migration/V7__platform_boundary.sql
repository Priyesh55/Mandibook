-- Platform database boundary (decision memo docs/AUTHENTICATION_SECURITY_DECISIONS.md, sections 3 and 4; blueprint 3.3).
-- ADDITIVE except one deliberate hardening of PostgreSQL's defaults (below). Rollback: db/rollback/U7__remove_platform_boundary.sql.
--
-- 1. HARDENING. By default PostgreSQL lets EVERY role (PUBLIC) use schema public and execute any function in it.
--    The memo's experiment showed what that means: a business SECURITY DEFINER function written for the application
--    could be called by the platform role and returned a firm name. So:
--      * USAGE on schema public is withdrawn from PUBLIC; each role that needs it is granted it explicitly
--        (R__runtime_grants.sql for mandi_app). The platform role is never granted it.
--      * EXECUTE on functions is withdrawn from PUBLIC, now and for functions created later by the migration role.
--    Trigger functions are unaffected: PostgreSQL checks EXECUTE when a trigger is created, not when it fires.
revoke usage on schema public from public;
revoke execute on all functions in schema public from public;
alter default privileges revoke execute on functions from public;

-- 2. CONTRACT SURFACE: the only ways data crosses between the two worlds. Both views are owned by the migration role,
--    so they read with the owner's rights; only the listed columns are visible, and no password hash, secret or token.

-- What the platform may see about an OWNER (not employees: who works for whom is business data). Owner users only,
-- through platform.owner_account. No firm, no employee, no business column. (MFA state is added with the MFA tables.)
create view platform.owner_user_summary as
select o.id                 as owner_account_id,
       u.id                 as user_id,
       u.email,
       u.display_name,
       u.status             as user_status,
       u.last_login_at,
       u.email_verified_at
  from platform.owner_account o
  join public.app_user u on u.id = o.owner_user_id;

-- What the business application may see about an owner's account: commercial state only, no personal data.
-- effective_status follows blueprint 10.2: an expired trial is DERIVED from the stored dates and the database clock,
-- never stored; a lapsed paid period is EXPIRED unless a manual grace date is still in the future.
create view public.account_access as
select o.owner_user_id,
       o.id                 as owner_account_id,
       o.status             as stored_status,
       o.trial_started_at,
       o.trial_ends_at,
       o.paid_until,
       o.access_grace_until,
       o.plan_code,
       case
           when o.status = 'SUSPENDED' then 'SUSPENDED'
           when o.status = 'TRIAL' and o.trial_ends_at is not null and now() >= o.trial_ends_at
                and not coalesce(o.access_grace_until > now(), false) then 'EXPIRED_TRIAL'
           when o.status = 'ACTIVE' and o.paid_until is not null and now() >= o.paid_until
                and not coalesce(o.access_grace_until > now(), false) then 'EXPIRED'
           else o.status
       end                  as effective_status
  from platform.owner_account o
 where o.owner_user_id is not null;
