-- Dev seed: loaded only by the dev profile (spring.flyway.locations in application-dev.yml).
-- Idempotent: fixed ids or natural keys, every insert is "on conflict do nothing".
-- Placeholder club and stadium (plan Q10); real data is entered at onboarding.

insert into organizers (id, name, kind, settings) values
  ('a7a5fc00-0000-4000-8000-000000000001', 'ATLAS FC', 'SPORTS_CLUB',
   '{"theme": {"accent": "#F5B700", "on_accent": "#0B0F1A", "crest_url": null, "display_font": null}}')
on conflict do nothing;

-- Cash points (plan Q54), so cash codes are offered in local dev. Added once, also to a dev database seeded before
-- they existed; an edited list is left alone.
update organizers set settings = settings || jsonb_build_object('cash_points', jsonb_build_array(
    jsonb_build_object('name', 'Guichet Grand Stade', 'address', 'Porte 1', 'hours', 'Lun-Sam 10:00-19:00'),
    jsonb_build_object('name', 'Boutique du club', 'address', 'Centre-ville', 'hours', 'Lun-Sam 09:00-18:00')))
where id = 'a7a5fc00-0000-4000-8000-000000000001' and not settings ? 'cash_points';

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

-- Demo accounts (docs/TESTING.md). Test phones, not real subscribers. With JOMHOR_DEV_FIXED_OTP set (scripts/dev.ps1)
-- every phone logs in with that code; the real code is still printed by LogOtpSender.
insert into users (id, phone_e164, display_name) values
  ('a7a5fc00-0000-4000-8000-000000000102', '+212600000002', 'Caissier Dev'),
  ('a7a5fc00-0000-4000-8000-000000000103', '+212600000003', 'Ops Dev'),
  ('a7a5fc00-0000-4000-8000-000000000104', '+212600000004', 'Finance Dev'),
  ('a7a5fc00-0000-4000-8000-000000000110', '+212600000010', 'Yassine Benali'),
  ('a7a5fc00-0000-4000-8000-000000000111', '+212600000011', 'Salma El Idrissi')
on conflict do nothing;

insert into staff (id, organizer_id, user_id, name, role) values
  ('a7a5fc00-0000-4000-8000-000000000202', 'a7a5fc00-0000-4000-8000-000000000001',
   'a7a5fc00-0000-4000-8000-000000000102', 'Caissier Dev', 'CASHIER'),
  ('a7a5fc00-0000-4000-8000-000000000203', 'a7a5fc00-0000-4000-8000-000000000001',
   'a7a5fc00-0000-4000-8000-000000000103', 'Ops Dev', 'OPS'),
  ('a7a5fc00-0000-4000-8000-000000000204', 'a7a5fc00-0000-4000-8000-000000000001',
   'a7a5fc00-0000-4000-8000-000000000104', 'Finance Dev', 'FINANCE')
on conflict do nothing;

-- PIN-only scanner staff (no account). Verifiers (docs/04-api.md §Gate PINs) of PIN 1234 (GATE) and 5678
-- (SUPERVISOR), fixed salts: dev PINs, a UI lock only.
insert into staff (id, organizer_id, name, role, pin_hash) values
  ('a7a5fc00-0000-4000-8000-000000000205', 'a7a5fc00-0000-4000-8000-000000000001', 'Agent Porte', 'GATE',
   'pbkdf2-sha256$10000$AAECAwQFBgcICQoLDA0ODw$keoFm64DM6KWn8jM18d4Ud2kP1rblrruokamJM30iew'),
  ('a7a5fc00-0000-4000-8000-000000000206', 'a7a5fc00-0000-4000-8000-000000000001', 'Chef de porte', 'SUPERVISOR',
   'pbkdf2-sha256$10000$EBESExQVFhcYGRobHB0eHw$Wzdm_KJN_gc6hqVgmslgQuahKrWpsf0rtnBoe55hD6I')
on conflict do nothing;

-- A retired scanner (Lot 4 BE-31, plan Q98): DevSeeder's past admissions of the demo fan point at it. Revoked, and
-- its public key is a placeholder byte, so it can never sign a request.
insert into devices (id, organizer_id, label, public_key, status, approved_at, revoked_at) values
  ('a7a5fc00-0000-4000-8000-000000000501', 'a7a5fc00-0000-4000-8000-000000000001', 'PDA archive', '\x00'::bytea,
   'REVOKED', now(), now())
on conflict do nothing;

-- ---------- multi-club (plan multi-club Q76) ----------
-- ATLAS FC is listed (docs/04-api.md §Club directory); a slug set by hand on a dev database is left alone.
update organizers set slug = 'atlas-fc' where id = 'a7a5fc00-0000-4000-8000-000000000001' and slug is null;

-- Second demo club, listed, no crest (clients draw the stand-in, plan Q72). The dev admin is ADMIN there too.
insert into organizers (id, name, kind, slug) values
  ('a7a5fc00-0000-4000-8000-000000000002', 'Olympique Tensift', 'SPORTS_CLUB', 'olympique-tensift')
on conflict do nothing;

insert into staff (id, organizer_id, user_id, name, role) values
  ('a7a5fc00-0000-4000-8000-000000000211', 'a7a5fc00-0000-4000-8000-000000000002',
   'a7a5fc00-0000-4000-8000-000000000101', 'Admin Dev', 'ADMIN')
on conflict do nothing;

insert into venues (id, organizer_id, name, city, capacity, timezone) values
  ('a7a5fc00-0000-4000-8000-000000000302', 'a7a5fc00-0000-4000-8000-000000000002',
   'Stade Tensift', 'Marrakech', 25000, 'Africa/Casablanca')
on conflict do nothing;

insert into zones (organizer_id, venue_id, code, name, capacity, seated) values
  ('a7a5fc00-0000-4000-8000-000000000002', 'a7a5fc00-0000-4000-8000-000000000302', 'NORD',    'Nord',    10000, false),
  ('a7a5fc00-0000-4000-8000-000000000002', 'a7a5fc00-0000-4000-8000-000000000302', 'SUD',     'Sud',     10000, false),
  ('a7a5fc00-0000-4000-8000-000000000002', 'a7a5fc00-0000-4000-8000-000000000302', 'HONNEUR', 'Honneur',  4000, true)
on conflict do nothing;

insert into gates (id, organizer_id, venue_id, code, name) values
  ('a7a5fc00-0000-4000-8000-000000000411', 'a7a5fc00-0000-4000-8000-000000000002', 'a7a5fc00-0000-4000-8000-000000000302', 'P1', 'Porte 1'),
  ('a7a5fc00-0000-4000-8000-000000000412', 'a7a5fc00-0000-4000-8000-000000000002', 'a7a5fc00-0000-4000-8000-000000000302', 'P2', 'Porte 2'),
  ('a7a5fc00-0000-4000-8000-000000000413', 'a7a5fc00-0000-4000-8000-000000000002', 'a7a5fc00-0000-4000-8000-000000000302', 'P3', 'Porte 3')
on conflict do nothing;

insert into gate_zones (organizer_id, gate_id, zone_id)
select g.organizer_id, g.id, z.id
from (values ('P1', 'HONNEUR'), ('P2', 'NORD'), ('P3', 'SUD')) as m(gate_code, zone_code)
join gates g on g.venue_id = 'a7a5fc00-0000-4000-8000-000000000302' and g.code = m.gate_code
join zones z on z.venue_id = 'a7a5fc00-0000-4000-8000-000000000302' and z.code = m.zone_code
on conflict do nothing;

-- Events, prices and the demo fan's tickets need K_e (encrypted in Java) and keep their times relative to now:
-- DevSeeder writes them at every dev startup.

-- Gate directions and locations (Lot 4 BE-32): made-up FR copy and made-up points around each stadium. Only gates
-- still without directions, so an edit made in the back-office survives the next startup.
update gates g set directions = m.directions, latitude = m.latitude, longitude = m.longitude
from (values
  ('a7a5fc00-0000-4000-8000-000000000401'::uuid, 'Tribune d''honneur, côté parvis principal. Entrée par l''esplanade, suivez le fléchage vert.', 33.583120, -7.647410),
  ('a7a5fc00-0000-4000-8000-000000000402'::uuid, 'Tribune d''honneur, côté parking VIP. Accès par la rue des Orangers.', 33.583460, -7.646720),
  ('a7a5fc00-0000-4000-8000-000000000403'::uuid, 'Angle Nord-Est. Depuis le tramway (station Stade), tournez à gauche et longez les grilles.', 33.584210, -7.645980),
  ('a7a5fc00-0000-4000-8000-000000000404'::uuid, 'Virage Nord, au bout de l''allée des Palmiers. Prévoyez 10 min depuis le parking P2.', 33.584650, -7.647120),
  ('a7a5fc00-0000-4000-8000-000000000405'::uuid, 'Entre le virage Nord et la tribune Est, face à la station de taxis.', 33.584020, -7.645310),
  ('a7a5fc00-0000-4000-8000-000000000406'::uuid, 'Tribune Est, au milieu de l''avenue du Stade. Fouille avant le portique.', 33.583180, -7.644890),
  ('a7a5fc00-0000-4000-8000-000000000407'::uuid, 'Virage Sud, côté boulevard. Depuis le parking P3, suivez le fléchage orange.', 33.581940, -7.646350),
  ('a7a5fc00-0000-4000-8000-000000000411'::uuid, 'Tribune d''honneur, entrée principale face à la route de l''Ourika.', 31.602410, -8.034520),
  ('a7a5fc00-0000-4000-8000-000000000412'::uuid, 'Virage Nord, à droite après le parking des bus. Suivez le fléchage bleu.', 31.603580, -8.035210),
  ('a7a5fc00-0000-4000-8000-000000000413'::uuid, 'Virage Sud, côté jardins. Accès piéton par l''allée des Oliviers.', 31.601270, -8.035840)
) as m(id, directions, latitude, longitude)
where g.id = m.id and g.directions is null;
