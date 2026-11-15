-- Lot 5 BE-49: the sold-out waitlist notice (docs/04-api.md §Notifications). Alone in its migration: Postgres can't
-- use a new enum value in the transaction that adds it (V28 does).
alter type message_kind add value 'WAITLIST_OPEN';
