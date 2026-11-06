-- ---------- payments ----------
create type payment_status as enum ('PENDING','SUCCEEDED','FAILED');

create table payments (
  id            uuid primary key default gen_random_uuid(),   -- sent to the gateway as its order reference
  organizer_id  uuid not null references organizers(id),
  order_id      uuid not null references orders(id),
  method        payment_method not null,
  provider      text not null,                        -- 'FAKE' (simulated, non-prod), 'PAYZONE'
  amount_mad    bigint not null check (amount_mad >= 0),
  status        payment_status not null default 'PENDING',
  provider_ref  text,                                 -- gateway transaction id from the callback
  callback      jsonb,                                -- last verified callback, card fields stripped
  created_at    timestamptz not null default now(),
  completed_at  timestamptz
);
create index on payments (order_id);
create unique index on payments (provider, provider_ref) where provider_ref is not null;

