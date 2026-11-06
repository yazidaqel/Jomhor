-- Dev seed: loaded only by the dev profile (spring.flyway.locations in application-dev.yml).
-- Idempotent: fixed ids or natural keys, every insert is "on conflict do nothing".
-- Placeholder club and stadium (plan Q10); real data is entered at onboarding.

insert into organizers (id, name, kind, settings) values
  ('a7a5fc00-0000-4000-8000-000000000001', 'ATLAS FC', 'SPORTS_CLUB',
   '{"theme": {"accent": "#F5B700", "on_accent": "#0B0F1A", "crest_url": null, "display_font": null}}')
on conflict do nothing;

-- Test phone, not a real subscriber. Log in with the code printed by LogOtpSender.
insert into users (id, phone_e164, display_name) values
  ('a7a5fc00-0000-4000-8000-000000000101', '+212600000001', 'Admin Dev')
on conflict do nothing;

insert into staff (id, organizer_id, user_id, name, role) values
  ('a7a5fc00-0000-4000-8000-000000000201', 'a7a5fc00-0000-4000-8000-000000000001',
   'a7a5fc00-0000-4000-8000-000000000101', 'Admin Dev', 'ADMIN')
on conflict do nothing;

insert into venues (id, organizer_id, name, city, capacity, timezone) values
  ('a7a5fc00-0000-4000-8000-000000000301', 'a7a5fc00-0000-4000-8000-000000000001',
   'Grand Stade', 'Casablanca', 45000, 'Africa/Casablanca')
on conflict do nothing;

-- zones.id is a smallserial (2 bytes in the QR): no fixed ids, the natural key is (venue_id, code).
insert into zones (organizer_id, venue_id, code, name, capacity, seated) values
  ('a7a5fc00-0000-4000-8000-000000000001', 'a7a5fc00-0000-4000-8000-000000000301', 'NORD',    'Nord',    12000, false),
  ('a7a5fc00-0000-4000-8000-000000000001', 'a7a5fc00-0000-4000-8000-000000000301', 'EST',     'Est',     14000, false),
  ('a7a5fc00-0000-4000-8000-000000000001', 'a7a5fc00-0000-4000-8000-000000000301', 'HONNEUR', 'Honneur',  6000, true),
  ('a7a5fc00-0000-4000-8000-000000000001', 'a7a5fc00-0000-4000-8000-000000000301', 'SUD',     'Sud',     12000, false)
on conflict do nothing;

insert into gates (id, organizer_id, venue_id, code, name) values
  ('a7a5fc00-0000-4000-8000-000000000401', 'a7a5fc00-0000-4000-8000-000000000001', 'a7a5fc00-0000-4000-8000-000000000301', 'P1', 'Porte 1'),
  ('a7a5fc00-0000-4000-8000-000000000402', 'a7a5fc00-0000-4000-8000-000000000001', 'a7a5fc00-0000-4000-8000-000000000301', 'P2', 'Porte 2'),
  ('a7a5fc00-0000-4000-8000-000000000403', 'a7a5fc00-0000-4000-8000-000000000001', 'a7a5fc00-0000-4000-8000-000000000301', 'P3', 'Porte 3'),
  ('a7a5fc00-0000-4000-8000-000000000404', 'a7a5fc00-0000-4000-8000-000000000001', 'a7a5fc00-0000-4000-8000-000000000301', 'P4', 'Porte 4'),
  ('a7a5fc00-0000-4000-8000-000000000405', 'a7a5fc00-0000-4000-8000-000000000001', 'a7a5fc00-0000-4000-8000-000000000301', 'P5', 'Porte 5'),
  ('a7a5fc00-0000-4000-8000-000000000406', 'a7a5fc00-0000-4000-8000-000000000001', 'a7a5fc00-0000-4000-8000-000000000301', 'P6', 'Porte 6'),
  ('a7a5fc00-0000-4000-8000-000000000407', 'a7a5fc00-0000-4000-8000-000000000001', 'a7a5fc00-0000-4000-8000-000000000301', 'P7', 'Porte 7')
on conflict do nothing;

-- Gate -> zone matrix from the B2 mockup (design/mockups/src/BoEvent.dc.html).
insert into gate_zones (organizer_id, gate_id, zone_id)
select g.organizer_id, g.id, z.id
from (values ('P1', 'HONNEUR'), ('P2', 'HONNEUR'), ('P3', 'NORD'), ('P3', 'EST'), ('P4', 'NORD'),
             ('P5', 'NORD'), ('P5', 'EST'), ('P6', 'EST'), ('P7', 'SUD')) as m(gate_code, zone_code)
join gates g on g.venue_id = 'a7a5fc00-0000-4000-8000-000000000301' and g.code = m.gate_code
join zones z on z.venue_id = 'a7a5fc00-0000-4000-8000-000000000301' and z.code = m.zone_code
on conflict do nothing;
