CREATE TABLE tickets (
    ticket_id uuid PRIMARY KEY CHECK (ticket_id <> '00000000-0000-0000-0000-000000000000'),
    payment_id uuid NOT NULL UNIQUE CHECK (payment_id <> '00000000-0000-0000-0000-000000000000'),
    event_id uuid NOT NULL CHECK (event_id <> '00000000-0000-0000-0000-000000000000'),
    buyer_id uuid NOT NULL CHECK (buyer_id <> '00000000-0000-0000-0000-000000000000'),
    seat_id varchar(64) NOT NULL CHECK (seat_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    event_name varchar(140) NOT NULL CHECK (length(btrim(event_name)) > 0 AND event_name !~ '[[:cntrl:]]'),
    venue varchar(140) NOT NULL CHECK (length(btrim(venue)) > 0 AND venue !~ '[[:cntrl:]]'),
    event_starts_at timestamptz NOT NULL,
    event_ends_at timestamptz NOT NULL,
    issued_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    status varchar(16) NOT NULL CHECK (status = 'ISSUED'),
    token text NOT NULL CHECK (length(token) <= 24000 AND token ~ '^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$'),
    payload_hash char(64) NOT NULL CHECK (payload_hash ~ '^[a-f0-9]{64}$'),
    key_id varchar(128) NOT NULL,
    payload_json text NOT NULL CHECK (octet_length(payload_json) <= 16384 AND jsonb_typeof(payload_json::jsonb) = 'object'),
    request_hash char(64) NOT NULL CHECK (request_hash ~ '^[a-f0-9]{64}$'),
    amount_minor bigint NOT NULL CHECK (amount_minor BETWEEN 0 AND 9999999999999),
    currency char(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT tickets_event_seat_unique UNIQUE (event_id, seat_id),
    CONSTRAINT tickets_dates CHECK (event_ends_at > event_starts_at AND expires_at = event_ends_at AND expires_at > issued_at)
);

CREATE INDEX tickets_issued_at_idx ON tickets (issued_at DESC, ticket_id DESC);

CREATE FUNCTION reject_ticket_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Los boletos emitidos son inmutables' USING ERRCODE = '23000';
END;
$$;

CREATE TRIGGER tickets_no_update_delete BEFORE UPDATE OR DELETE ON tickets
FOR EACH ROW EXECUTE FUNCTION reject_ticket_mutation();
CREATE TRIGGER tickets_no_truncate BEFORE TRUNCATE ON tickets
FOR EACH STATEMENT EXECUTE FUNCTION reject_ticket_mutation();

REVOKE ALL ON TABLE tickets FROM PUBLIC, ticket_engine_app;
REVOKE CREATE ON SCHEMA public FROM PUBLIC, ticket_engine_app;
GRANT USAGE ON SCHEMA public TO ticket_engine_app;
GRANT SELECT, INSERT ON TABLE tickets TO ticket_engine_app;
REVOKE ALL ON FUNCTION reject_ticket_mutation() FROM PUBLIC, ticket_engine_app;
