-- Authentication storage (blueprint 6, 9.3; P1-04): server-side sessions, the security event log and the login throttle.
-- ADDITIVE ONLY. Rollback: db/rollback/U8__remove_authentication.sql (tested).
-- All three live in schema public (the business application's world). The platform role has no privilege on any of
-- them (R__platform_grants.sql lists nothing of this migration), and the platform application uses its own session
-- store later: there is no cross-boundary session lookup.

-- ============================== Spring Session JDBC (standard PostgreSQL schema) ==============================
-- Created here, by Flyway, and never by Spring (spring.session.jdbc.initialize-schema=never): the application role
-- must not be able to create tables. Names are the library's own (it queries them unquoted).
create table spring_session (
    primary_id            char(36)    not null,
    session_id            char(36)    not null,
    creation_time         bigint      not null,
    last_access_time      bigint      not null,
    max_inactive_interval int         not null,
    expiry_time           bigint      not null,
    principal_name        varchar(100),
    constraint spring_session_pk primary key (primary_id)
);
create unique index spring_session_ix1 on spring_session (session_id);
create index spring_session_ix2 on spring_session (expiry_time);
create index spring_session_ix3 on spring_session (principal_name);

create table spring_session_attributes (
    session_primary_id char(36)     not null,
    attribute_name     varchar(200) not null,
    attribute_bytes    bytea        not null,
    constraint spring_session_attributes_pk primary key (session_primary_id, attribute_name),
    constraint spring_session_attributes_fk foreign key (session_primary_id) references spring_session (primary_id) on delete cascade
);

-- ============================== security event log (append-only) ==============================
-- Who did what to an account's security, never the secret itself: no password, token or session id is ever stored.
-- An unknown email is kept only as an HMAC (the raw address of a stranger is not stored); the IP only as an HMAC.
create table auth_event (
    id          bigint generated always as identity primary key,
    event_type  varchar(40) not null,
    user_id     bigint,
    email_hmac  bytea,
    ip_hmac     bytea,
    request_id  uuid,
    details     varchar(200),
    created_at  timestamptz not null default now(),
    constraint auth_event_user_fk foreign key (user_id) references app_user (id),
    constraint auth_event_type_ck check (event_type in (
        'LOGIN_SUCCESS', 'LOGIN_FAILURE', 'LOGIN_THROTTLED', 'LOGIN_LOCKED', 'LOGOUT',
        'SESSION_ABSOLUTE_EXPIRED', 'PASSWORD_CHANGED', 'ACCOUNT_DISABLED', 'ACCOUNT_ENABLED', 'SESSIONS_REVOKED')),
    constraint auth_event_hmac_len_ck check ((email_hmac is null or octet_length(email_hmac) = 32)
                                          and (ip_hmac is null or octet_length(ip_hmac) = 32))
);
create index idx_auth_event_user on auth_event (user_id, created_at desc) where user_id is not null;
create index idx_auth_event_time on auth_event (created_at);

create function auth_event_is_append_only() returns trigger language plpgsql as $$
begin
    raise exception 'auth_event is append-only' using errcode = '23514';
end $$;
create trigger auth_event_no_update before update on auth_event for each row execute function auth_event_is_append_only();
create trigger auth_event_no_delete before delete on auth_event for each row execute function auth_event_is_append_only();
create trigger auth_event_no_truncate before truncate on auth_event for each statement execute function auth_event_is_append_only();

-- ============================== login throttle (shared by all instances) ==============================
-- One fixed-window counter per HMAC key (an email or an address); updated by a single atomic upsert.
create table auth_throttle (
    key_hmac     bytea       primary key,
    window_start timestamptz not null,
    attempts     integer     not null,
    constraint auth_throttle_key_len_ck check (octet_length(key_hmac) = 32),
    constraint auth_throttle_attempts_ck check (attempts >= 0)
);
create index idx_auth_throttle_window on auth_throttle (window_start);
