-- Lot 4 BE-29: split pay (docs/04-api.md §Split pay) and the attempt's return channel (P77).
alter table orders add column group_order_id uuid references orders(id);
create index on orders (group_order_id) where group_order_id is not null;
alter table payments add column return_channel sales_channel check (return_channel in ('APP','WEB'));
