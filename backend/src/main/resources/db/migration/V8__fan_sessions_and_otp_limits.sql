-- BE-9 (Lot 1 plan Q13, Q14, P7, P8, P12). Names as docs/03-schema.sql.
-- Tenant rule exceptions are now four platform identity tables:
-- organizers, users, otp_challenges, fan_sessions (one fan account across organizers).

alter table otp_challenges
  add column audience text not null default 'fan' check (audience in ('fan','org')),  -- a code verifies only on its own audience
  add column ip       inet;                             -- requester (trusted-proxy resolved), for the per-IP throttle

drop index otp_challenges_phone_e164_created_at_idx;
create index on otp_challenges (phone_e164, audience, created_at desc);
  -- per phone+audience: 3 sends / 10 min, 10 sends / 24 h; sum(attempts) of failed codes >= 15 in 24 h -> locked
create index on otp_challenges (ip, created_at desc);   -- per IP: docs/04-api.md §Fan auth

create table fan_sessions (
  id            uuid primary key default gen_random_uuid(),   -- JWT `sid`
  user_id       uuid not null references users(id),
  created_at    timestamptz not null default now(),
  expires_at    timestamptz not null,                 -- = JWT exp (30 d)
  revoked_at    timestamptz
);
create index on fan_sessions (user_id) where revoked_at is null;
