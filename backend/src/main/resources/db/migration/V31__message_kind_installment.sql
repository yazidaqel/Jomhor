-- Lot 5 BE-57: the installment reminder (docs/04-api.md §Notifications INSTALLMENT_DUE). Alone in its migration:
-- Postgres can't use a new enum value in the transaction that adds it (V32 does).
alter type message_kind add value 'INSTALLMENT_DUE';
