-- Identity core (blueprint docs/AUTHENTICATION_AND_MULTI_TENANT_SECURITY_BLUEPRINT.md, sections 3, 4.3, 7; decisions in
-- docs/AUTHENTICATION_SECURITY_DECISIONS.md). ADDITIVE ONLY: no existing table, row or constraint is changed.
-- Rollback: db/rollback/U6__remove_identity.sql (tested; refuses to run when identity rows exist).
--
-- Two separate worlds:
--   * schema public   - business identities (owners and employees), explicit firm membership, invitation and
--                       recovery tokens. Accessible to the restricted runtime role (grants: R__runtime_grants.sql).
--   * schema platform - platform administrators and owner ACCOUNTS (commercial state). No table here references a
--                       business table, and no business table references a platform table: a platform administrator can
--                       never appear in firm_member and the identity schema gives the platform no path to business data.
--                       The runtime role gets NO privilege on this schema; the platform role and its grants arrive with
--                       the platform boundary migration.
-- Tokens are never stored: only the SHA-256 digest (32 bytes) of a 256-bit random token. Passwords are never stored:
-- only a delegating-encoder hash ({bcrypt}...), which the CHECK constraints below insist on.

-- ============================== public: business identities ==============================

create table app_user (
    id                  bigint generated always as identity primary key,
    -- trimmed and lower-cased by the application (also Unicode-normalized there); the database refuses anything else
    email               varchar(254) not null,
    display_name        varchar(200),
    -- '{id}hash' as written by Spring's DelegatingPasswordEncoder; a plaintext password cannot satisfy this
    password_hash       varchar(100) not null,
    status              varchar(20)  not null default 'ACTIVE',
    failed_login_count  integer      not null default 0,
    locked_until        timestamptz,
    password_changed_at timestamptz  not null default now(),
    last_login_at       timestamptz,
    email_verified_at   timestamptz,
    created_at          timestamptz  not null default now(),
    version             bigint       not null default 0,
    constraint app_user_email_uk unique (email),
    constraint app_user_email_form_ck check (email = lower(btrim(email)) and email <> '' and position('@' in email) > 1),
    constraint app_user_password_hash_form_ck check (password_hash ~ '^\{[a-z0-9]{3,20}\}\S{40,}$'),
    constraint app_user_status_ck check (status in ('ACTIVE', 'DISABLED')),
    constraint app_user_failed_login_ck check (failed_login_count >= 0)
);

-- The only source of authorization to a firm. Owner-ness belongs to the membership, not to the user. Rows are never
-- deleted (history and audit keep resolving); a person leaves a firm by becoming INACTIVE.
create table firm_member (
    id                    bigint generated always as identity primary key,
    firm_id               bigint      not null,
    user_id               bigint      not null,
    role                  varchar(20) not null,
    status                varchar(20) not null default 'ACTIVE',
    created_by_user_id    bigint,
    created_at            timestamptz not null default now(),
    deactivated_at        timestamptz,
    deactivated_by_user_id bigint,
    constraint firm_member_firm_fk foreign key (firm_id) references firm (id),
    constraint firm_member_user_fk foreign key (user_id) references app_user (id),
    constraint firm_member_created_by_fk foreign key (created_by_user_id) references app_user (id),
    constraint firm_member_deactivated_by_fk foreign key (deactivated_by_user_id) references app_user (id),
    constraint firm_member_firm_user_uk unique (firm_id, user_id),
    -- target of composite foreign keys (audit and invitations) that must stay inside one firm
    constraint firm_member_id_firm_uk unique (id, firm_id),
    constraint firm_member_role_ck check (role in ('OWNER', 'EMPLOYEE')),
    constraint firm_member_status_ck check (status in ('ACTIVE', 'INACTIVE')),
    constraint firm_member_inactive_ck check (status <> 'INACTIVE' or deactivated_at is not null)
);
-- exactly one OWNER membership per firm (co-owners and ownership transfer are deferred, blueprint Q-11)
create unique index firm_member_one_owner_uk on firm_member (firm_id) where role = 'OWNER';
-- "which firms may this person use" (paged firm list)
create index idx_firm_member_user_status on firm_member (user_id, status);

-- Membership identity never moves: audit rows and invitations point at (id, firm_id).
create function firm_member_keys_are_immutable() returns trigger language plpgsql as $$
begin
    if new.firm_id <> old.firm_id or new.user_id <> old.user_id then
        raise exception 'firm_member.firm_id and user_id cannot be changed' using errcode = '23514';
    end if;
    return new;
end $$;
create trigger firm_member_keys_immutable before update on firm_member
    for each row execute function firm_member_keys_are_immutable();

-- Identities and memberships are never physically deleted.
create function identity_rows_are_not_deleted() returns trigger language plpgsql as $$
begin
    raise exception '% rows are never deleted (disable or deactivate instead)', tg_table_name using errcode = '23514';
end $$;
create trigger app_user_no_delete before delete on app_user
    for each row execute function identity_rows_are_not_deleted();
create trigger firm_member_no_delete before delete on firm_member
    for each row execute function identity_rows_are_not_deleted();

-- Password reset and email verification. Only the SHA-256 digest of the emailed 256-bit token is stored.
create table credential_token (
    id                bigint generated always as identity primary key,
    user_id           bigint      not null,
    purpose           varchar(20) not null,
    token_hash        bytea       not null,
    expires_at        timestamptz not null,
    consumed_at       timestamptz,
    superseded_at     timestamptz,
    created_at        timestamptz not null default now(),
    requested_ip_hash bytea,
    constraint credential_token_user_fk foreign key (user_id) references app_user (id),
    constraint credential_token_hash_uk unique (token_hash),
    constraint credential_token_hash_len_ck check (octet_length(token_hash) = 32),
    constraint credential_token_purpose_ck check (purpose in ('PASSWORD_RESET', 'EMAIL_VERIFY')),
    constraint credential_token_expiry_ck check (expires_at > created_at),
    constraint credential_token_one_end_ck check (consumed_at is null or superseded_at is null)
);
create index idx_credential_token_open on credential_token (user_id, purpose) where consumed_at is null and superseded_at is null;
create index idx_credential_token_expiry on credential_token (expires_at);

-- Employee invitation to one firm. Invited by an (active) membership of the same firm.
create table firm_invitation (
    id                         bigint generated always as identity primary key,
    firm_id                    bigint       not null,
    email                      varchar(254) not null,
    role                       varchar(20)  not null default 'EMPLOYEE',
    token_hash                 bytea        not null,
    invited_by_membership_id   bigint       not null,
    expires_at                 timestamptz  not null,
    accepted_at                timestamptz,
    accepted_by_user_id        bigint,
    revoked_at                 timestamptz,
    created_at                 timestamptz  not null default now(),
    constraint firm_invitation_firm_fk foreign key (firm_id) references firm (id),
    constraint firm_invitation_inviter_fk foreign key (invited_by_membership_id, firm_id) references firm_member (id, firm_id),
    constraint firm_invitation_acceptor_fk foreign key (accepted_by_user_id) references app_user (id),
    constraint firm_invitation_hash_uk unique (token_hash),
    constraint firm_invitation_hash_len_ck check (octet_length(token_hash) = 32),
    constraint firm_invitation_email_form_ck check (email = lower(btrim(email)) and email <> '' and position('@' in email) > 1),
    -- only employees are invited; the owner of a firm is its creator
    constraint firm_invitation_role_ck check (role = 'EMPLOYEE'),
    constraint firm_invitation_expiry_ck check (expires_at > created_at),
    constraint firm_invitation_one_end_ck check (accepted_at is null or revoked_at is null),
    constraint firm_invitation_acceptor_ck check ((accepted_at is null) = (accepted_by_user_id is null))
);
-- one open invitation per firm and email (a resend revokes the previous one first)
create unique index firm_invitation_open_uk on firm_invitation (firm_id, email) where accepted_at is null and revoked_at is null;
create index idx_firm_invitation_expiry on firm_invitation (expires_at) where accepted_at is null and revoked_at is null;

-- ============================== platform: administrators and owner accounts ==============================
-- Created by the migration (owner) role. Nothing is granted to the runtime role `mandi_app`; the platform role and its
-- grants belong to the platform boundary migration. There is deliberately NO foreign key between this schema and
-- the public identity/business tables (a platform administrator is not an app_user, and an FK error message could
-- reveal whether an id exists on the other side); links are plain ids resolved by audited functions later.

create schema platform;

create table platform.platform_admin (
    id                  bigint generated always as identity primary key,
    email               varchar(254) not null,
    display_name        varchar(200),
    password_hash       varchar(100) not null,
    status              varchar(20)  not null default 'ACTIVE',
    failed_login_count  integer      not null default 0,
    locked_until        timestamptz,
    password_changed_at timestamptz  not null default now(),
    last_login_at       timestamptz,
    created_at          timestamptz  not null default now(),
    version             bigint       not null default 0,
    constraint platform_admin_email_uk unique (email),
    constraint platform_admin_email_form_ck check (email = lower(btrim(email)) and email <> '' and position('@' in email) > 1),
    constraint platform_admin_password_hash_form_ck check (password_hash ~ '^\{[a-z0-9]{3,20}\}\S{40,}$'),
    constraint platform_admin_status_ck check (status in ('ACTIVE', 'DISABLED')),
    constraint platform_admin_failed_login_ck check (failed_login_count >= 0)
);

-- Commercial account of one owner (trial and subscription state only; no business data).
create table platform.owner_account (
    id                    bigint generated always as identity primary key,
    display_name          varchar(200) not null,
    status                varchar(20)  not null default 'TRIAL',
    origin                varchar(20)  not null default 'ADMIN',
    plan_code             varchar(30)  not null default 'STANDARD',
    -- the trial starts when the owner accepts the invitation (blueprint RD-8): both dates are set together then
    trial_started_at      timestamptz,
    trial_ends_at         timestamptz,
    paid_until            timestamptz,
    access_grace_until    timestamptz,
    suspended_reason_code varchar(30),
    -- plain id of public.app_user once the invitation is accepted (no cross-schema foreign key, see above)
    owner_user_id         bigint,
    created_by_admin_id   bigint,
    created_at            timestamptz  not null default now(),
    version               bigint       not null default 0,
    constraint owner_account_admin_fk foreign key (created_by_admin_id) references platform.platform_admin (id),
    constraint owner_account_status_ck check (status in ('TRIAL', 'ACTIVE', 'SUSPENDED')),
    constraint owner_account_origin_ck check (origin in ('ADMIN', 'SELF_SERVICE')),
    constraint owner_account_trial_dates_ck check ((trial_started_at is null) = (trial_ends_at is null)),
    constraint owner_account_trial_order_ck check (trial_ends_at is null or trial_ends_at > trial_started_at),
    -- an owner whose invitation was accepted and who is on trial must have a trial period
    constraint owner_account_trial_started_ck check (status <> 'TRIAL' or owner_user_id is null or trial_started_at is not null),
    constraint owner_account_suspension_ck check ((status = 'SUSPENDED') = (suspended_reason_code is not null)),
    constraint owner_account_reason_ck check (suspended_reason_code is null
        or suspended_reason_code in ('NON_PAYMENT', 'SECURITY_REVIEW', 'OWNER_REQUEST', 'OTHER'))
);
create unique index owner_account_owner_user_uk on platform.owner_account (owner_user_id) where owner_user_id is not null;
create index idx_owner_account_trial_end on platform.owner_account (trial_ends_at) where status = 'TRIAL';

-- Owner invitation. The administrator never sees the token: only its SHA-256 digest is stored.
create table platform.owner_invitation (
    id                  bigint generated always as identity primary key,
    owner_account_id    bigint       not null,
    email               varchar(254) not null,
    token_hash          bytea        not null,
    expires_at          timestamptz  not null,
    accepted_at         timestamptz,
    revoked_at          timestamptz,
    created_by_admin_id bigint,
    created_at          timestamptz  not null default now(),
    constraint owner_invitation_account_fk foreign key (owner_account_id) references platform.owner_account (id),
    constraint owner_invitation_admin_fk foreign key (created_by_admin_id) references platform.platform_admin (id),
    constraint owner_invitation_hash_uk unique (token_hash),
    constraint owner_invitation_hash_len_ck check (octet_length(token_hash) = 32),
    constraint owner_invitation_email_form_ck check (email = lower(btrim(email)) and email <> '' and position('@' in email) > 1),
    constraint owner_invitation_expiry_ck check (expires_at > created_at),
    constraint owner_invitation_one_end_ck check (accepted_at is null or revoked_at is null)
);
create unique index owner_invitation_open_uk on platform.owner_invitation (owner_account_id) where accepted_at is null and revoked_at is null;
