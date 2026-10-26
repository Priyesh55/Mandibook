-- V12: a farmer belongs to a village (optional). The bill workflow is village first: the farmer list of a bill shows the
-- farmers of the chosen village. ADDITIVE: existing farmers keep a NULL village and stay valid; nothing is guessed or backfilled.
-- The composite key makes it impossible to point at a village of another firm. Rollback: db/rollback/U12__remove_farmer_village.sql.
alter table farmer add column village_id bigint;
alter table farmer add constraint farmer_village_firm_fk foreign key (village_id, firm_id) references village (id, firm_id);
create index idx_farmer_village on farmer (firm_id, village_id);
