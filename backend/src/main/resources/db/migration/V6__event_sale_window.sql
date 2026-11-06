-- Scheduled sale opening (plan Q19/P6, docs/03-schema.sql events). Null = on sale from publish.
-- Rule sale_starts_at < starts_at - 60 min is checked by EventService (422 on sale_starts_at).
alter table events add column sale_starts_at timestamptz;
