-- BE-22 (Lot 3): cash codes and the hosted-page marker on payments (docs/03-schema.sql payments).
-- pay_code: CASH_AGENCY only, 6 Crockford base32 symbols (docs/04-api.md §Cash codes), unique forever.
-- redirected_at: CARD/WALLET hosted page opened; the expiry waits only for these (plan P38).
alter table payments add column pay_code text unique;
alter table payments add column redirected_at timestamptz;
alter table payments add constraint payments_pay_code check ((method = 'CASH_AGENCY') = (pay_code is not null));
create index on payments (organizer_id, completed_at) where status = 'SUCCEEDED';   -- cash report
