-- Per-venue IANA time zone (plan Q7, docs/03-schema.sql venues). Existing rows get the default.
alter table venues add column timezone text not null default 'Africa/Casablanca'; -- IANA zone; every wall time for this venue's events
