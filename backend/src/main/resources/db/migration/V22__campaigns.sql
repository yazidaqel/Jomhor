-- Lot 4 BE-33: campaigns (docs/04-api.md §Campaigns). Names as docs/03-schema.sql.

create type campaign_status   as enum ('DRAFT','SCHEDULED','SENDING','SENT','CANCELLED');
create type campaign_audience as enum ('MEMBERS','EVENT_HOLDERS');

create table campaigns (
  id            uuid primary key default gen_random_uuid(),
  organizer_id  uuid not null references organizers(id),
  channel       message_channel not null,
  audience      campaign_audience not null,     -- MEMBERS: opted-in members (marketing); EVENT_HOLDERS: VALID holders (service info)
  event_id      integer references events(id),  -- required for EVENT_HOLDERS; the push deep link
  title         text not null,                  -- 1-60 chars, any script
  body          text not null,                  -- 1-500 chars, any script
  status        campaign_status not null default 'DRAFT',
  scheduled_at  timestamptz,
  created_by    uuid not null references staff(id),
  created_at    timestamptz not null default now(),
  started_at    timestamptz,                    -- audience snapshot taken
  finished_at   timestamptz,
  constraint campaigns_event check (audience <> 'EVENT_HOLDERS' or event_id is not null)
);
create index on campaigns (organizer_id, created_at desc);
create index on campaigns (scheduled_at) where status = 'SCHEDULED';

alter table fan_messages add column campaign_id uuid references campaigns(id);
alter table fan_messages add constraint fan_messages_campaign check ((kind = 'CAMPAIGN') = (campaign_id is not null));
create unique index on fan_messages (campaign_id, user_id) where campaign_id is not null;          -- one per recipient
create index on fan_messages (user_id, created_at desc) where kind = 'CAMPAIGN' and channel = 'PUSH';  -- inbox
