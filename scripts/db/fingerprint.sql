-- Read-only fingerprint of a database: used to compare the original with a restored copy.
-- Usage: psql -X -A -t -f fingerprint.sql   (prints one line per fact)
select 'rows.' || relname || '=' || (xpath('/row/c/text()', query_to_xml(format('select count(*) as c from public.%I', relname), false, true, '')))[1]::text
from pg_class where relkind = 'r' and relnamespace = 'public'::regnamespace order by relname;
select 'flyway.rows=' || count(*) || ' ok=' || count(*) filter (where success) from flyway_schema_history;
select 'flyway.versions=' || coalesce(string_agg(version, ',' order by installed_rank), '') from flyway_schema_history where version is not null;
select 'constraints.md5=' || md5(string_agg(conrelid::regclass::text || ':' || conname || ':' || pg_get_constraintdef(oid), '|' order by conrelid::regclass::text, conname))
from pg_constraint where connamespace = 'public'::regnamespace;
select 'indexes.md5=' || md5(string_agg(indexdef, '|' order by indexname)) from pg_indexes where schemaname = 'public';
select 'columns.md5=' || md5(string_agg(table_name || '.' || column_name || ':' || data_type || ':' || coalesce(character_maximum_length::text, '') || ':' || coalesce(numeric_precision::text, '') || ':' || is_nullable, '|' order by table_name, column_name))
from information_schema.columns where table_schema = 'public';
select 'triggers=' || coalesce(string_agg(tgrelid::regclass::text || '.' || tgname || '/enabled=' || tgenabled::text, ',' order by tgname), '')
from pg_trigger where not tgisinternal;
select 'firms=' || count(*) from firm;
