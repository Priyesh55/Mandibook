-- ADDITIVE ONLY. Rollback: db/rollback/U10__remove_audit_actors_and_bill_idempotency.sql.
--
-- 1. AUDIT ACTORS (blueprint 9.2, decision memo section 8). New audit rows say WHO acted (person, membership in that firm,
--    role at the time of the action) and which request. Existing rows are NOT touched: they keep actor 'anonymous' and the new
--    columns stay NULL (nothing is backfilled or guessed; adding nullable columns without a default does not fire the
--    append-only triggers and rewrites no row).
alter table audit_event add column actor_user_id       bigint;
alter table audit_event add column actor_membership_id bigint;
alter table audit_event add column actor_role          varchar(20);
alter table audit_event add column request_id          uuid;

alter table audit_event add constraint audit_event_actor_user_fk foreign key (actor_user_id) references app_user (id);
-- the membership must belong to the same firm as the event
alter table audit_event add constraint audit_event_actor_membership_fk
    foreign key (actor_membership_id, firm_id) references firm_member (id, firm_id);
alter table audit_event add constraint audit_event_actor_role_ck check (actor_role is null or actor_role in ('OWNER', 'EMPLOYEE'));
-- "what did this person do": partial index, small because old rows have no actor
create index idx_audit_event_actor on audit_event (actor_user_id, created_at desc) where actor_user_id is not null;

-- 2. SAFE RETRIES OF A BILL SAVE. A client that is not sure whether its save arrived (timeout, lost response) retries with the
--    SAME Idempotency-Key and gets the SAME bill back instead of a second one. The key is unique within a firm; the stored
--    fingerprint of the request makes a reused key with different content a conflict instead of a silent replay.
alter table aadat_bill add column idempotency_key varchar(64);
alter table aadat_bill add column request_hash    bytea;
alter table aadat_bill add constraint aadat_bill_idempotency_key_ck
    check (idempotency_key is null or idempotency_key ~ '^[A-Za-z0-9_-]{8,64}$');
alter table aadat_bill add constraint aadat_bill_request_hash_ck
    check ((idempotency_key is null) = (request_hash is null) and (request_hash is null or octet_length(request_hash) = 32));
create unique index aadat_bill_idempotency_uk on aadat_bill (firm_id, idempotency_key) where idempotency_key is not null;
