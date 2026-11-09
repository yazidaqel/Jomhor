-- Lot 4 BE-32: how to reach a gate and where it is (fan app only; never in the manifest payload).
alter table gates
  add column directions text,
  add column latitude   numeric(8,6) check (latitude between -90 and 90),
  add column longitude  numeric(9,6) check (longitude between -180 and 180),
  add constraint gates_location check ((latitude is null) = (longitude is null));
