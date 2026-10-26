-- Append-only audit history. Written in the same transaction as the change it describes.
-- There is no login yet, so the actor is recorded as 'anonymous' (see docs/ASSUMPTIONS.md A8).
create table audit_event (
    id bigint generated always as identity primary key,
    entity_type varchar(40) not null,
    entity_id bigint not null,
    action varchar(30) not null,
    details varchar(1000),
    actor varchar(100) not null default 'anonymous',
    created_at timestamptz not null default now()
);
create index idx_audit_entity on audit_event(entity_type, entity_id, id);

-- The database itself refuses to change or remove history (not only the application).
create function audit_event_immutable() returns trigger as $$
begin
    raise exception 'audit_event is append-only: % is not allowed', tg_op;
end;
$$ language plpgsql;

create trigger audit_event_no_change
    before update or delete on audit_event
    for each row execute function audit_event_immutable();

create trigger audit_event_no_truncate
    before truncate on audit_event
    for each statement execute function audit_event_immutable();
