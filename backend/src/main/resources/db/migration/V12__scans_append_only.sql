-- BE-20 (Lot 2): scans are append-only at the database level too (docs/03-schema.sql gate section). Any UPDATE,
-- DELETE or TRUNCATE of scans raises, even one that touches no row.

create function scans_append_only() returns trigger language plpgsql as $$
begin
  raise exception 'scans is append-only: % refused', tg_op using errcode = 'restrict_violation';
end
$$;

create trigger scans_append_only before update or delete or truncate on scans
  for each statement execute function scans_append_only();
