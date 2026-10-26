-- SYNTHETIC DEMONSTRATION DATA ONLY. Fictitious names. Not derived from any legacy record.
-- Repeatable and idempotent: it always runs after the versioned schema migrations, never occupies a version
-- number, and can run again without creating duplicates. Rows belong to the DEFAULT firm.
insert into village (name, name_hi, firm_id)
select v.name, v.name_hi, f.id
from (values ('Demo Village A', 'डेमो गाँव ए'),
             ('Demo Village B', 'डेमो गाँव बी')) as v(name, name_hi)
cross join (select id from firm where code = 'DEFAULT') f
where not exists (select 1 from village x where x.name = v.name and x.firm_id = f.id);

insert into farmer (name, name_hi, firm_id)
select v.name, v.name_hi, f.id
from (values ('Demo Farmer 1', 'डेमो किसान 1'),
             ('Demo Farmer 2', 'डेमो किसान 2')) as v(name, name_hi)
cross join (select id from firm where code = 'DEFAULT') f
where not exists (select 1 from farmer x where x.name = v.name and x.firm_id = f.id);

insert into buyer (name, name_hi, firm_id)
select v.name, v.name_hi, f.id
from (values ('Demo Buyer 1', 'डेमो खरीदार 1'),
             ('Demo Buyer 2', 'डेमो खरीदार 2')) as v(name, name_hi)
cross join (select id from firm where code = 'DEFAULT') f
where not exists (select 1 from buyer x where x.name = v.name and x.firm_id = f.id);

-- Placeholder expense heads: NOT the real expense heads (those are unconfirmed, question Q5).
insert into expense_head (name, name_hi, firm_id)
select v.name, v.name_hi, f.id
from (values ('Demo Expense A (placeholder)', 'डेमो खर्च ए (प्लेसहोल्डर)'),
             ('Demo Expense B (placeholder)', 'डेमो खर्च बी (प्लेसहोल्डर)')) as v(name, name_hi)
cross join (select id from firm where code = 'DEFAULT') f
where not exists (select 1 from expense_head x where x.name = v.name and x.firm_id = f.id);
