-- BE-16: GET /org/events/{id}/sales reads an organizer's payment.refund_required rows, newest first. Names as docs/03-schema.sql.
create index on audit_log (organizer_id, action, at);
