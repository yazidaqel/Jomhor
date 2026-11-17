-- Venue map (plan venue-map BE-73, docs/03-schema.sql venues.layout, zones.map_slot). Display only: never read by
-- orders, tickets, the QR, the manifest or the scanner. Existing rows get null (no map: zones shown as a list).
alter table venues add column layout text;    -- docs/venue-layouts.json layout id; validated by PUT /org/venues/{id}/map
alter table zones add column map_slot text;   -- slot id in the venue's layout; null = not on the map
alter table zones add constraint zones_venue_id_map_slot_key unique (venue_id, map_slot); -- one zone per slot; nulls never collide
