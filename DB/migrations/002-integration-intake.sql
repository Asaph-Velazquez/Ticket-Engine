-- Integración Módulo 2 (detalle de reserva) + Módulo 3 (pago confirmado).
-- Los identificadores externos dejan de ser UUID: M3 usa pay_/ord_/res_/usr_.

ALTER TABLE tickets
    DROP CONSTRAINT tickets_payment_id_check,
    DROP CONSTRAINT tickets_event_id_check,
    DROP CONSTRAINT tickets_buyer_id_check;

ALTER TABLE tickets
    ALTER COLUMN payment_id TYPE varchar(64) USING payment_id::text,
    ALTER COLUMN event_id TYPE varchar(64) USING event_id::text,
    ALTER COLUMN buyer_id TYPE varchar(64) USING buyer_id::text;

ALTER TABLE tickets
    ADD CONSTRAINT tickets_payment_id_format CHECK (payment_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    ADD CONSTRAINT tickets_event_id_format CHECK (event_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    ADD CONSTRAINT tickets_buyer_id_format CHECK (buyer_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    -- Nulos solo en boletos emitidos antes de esta migración.
    ADD COLUMN order_id varchar(64) CHECK (order_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    ADD COLUMN reservation_id varchar(64) UNIQUE CHECK (reservation_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$');

CREATE INDEX tickets_buyer_idx ON tickets (buyer_id, event_starts_at, ticket_id);

-- Confirmación de pago recibida del Módulo 3; espera el detalle del Módulo 2 si aún no llega.
CREATE TABLE payment_confirmations (
    payment_id varchar(64) PRIMARY KEY CHECK (payment_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    reservation_id varchar(64) NOT NULL UNIQUE CHECK (reservation_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    order_id varchar(64) NOT NULL CHECK (order_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    buyer_id varchar(64) NOT NULL CHECK (buyer_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    amount_minor bigint NOT NULL CHECK (amount_minor BETWEEN 0 AND 9999999999999),
    currency char(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    paid_at timestamptz NOT NULL,
    request_hash char(64) NOT NULL CHECK (request_hash ~ '^[a-f0-9]{64}$'),
    received_at timestamptz NOT NULL
);

-- Detalle de evento y asiento recibido del Módulo 2; espera el pago si aún no llega.
CREATE TABLE reservation_details (
    reservation_id varchar(64) PRIMARY KEY CHECK (reservation_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    event_id varchar(64) NOT NULL CHECK (event_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    seat_id varchar(64) NOT NULL CHECK (seat_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    event_name varchar(140) NOT NULL CHECK (length(btrim(event_name)) > 0 AND event_name !~ '[[:cntrl:]]'),
    venue varchar(140) NOT NULL CHECK (length(btrim(venue)) > 0 AND venue !~ '[[:cntrl:]]'),
    event_starts_at timestamptz NOT NULL,
    event_ends_at timestamptz NOT NULL,
    request_hash char(64) NOT NULL CHECK (request_hash ~ '^[a-f0-9]{64}$'),
    received_at timestamptz NOT NULL,
    CONSTRAINT reservation_details_dates CHECK (event_ends_at > event_starts_at)
);

-- Ambos registros son evidencia de lo recibido: inmutables como los boletos.
CREATE TRIGGER payment_confirmations_no_update_delete BEFORE UPDATE OR DELETE ON payment_confirmations
FOR EACH ROW EXECUTE FUNCTION reject_ticket_mutation();
CREATE TRIGGER payment_confirmations_no_truncate BEFORE TRUNCATE ON payment_confirmations
FOR EACH STATEMENT EXECUTE FUNCTION reject_ticket_mutation();
CREATE TRIGGER reservation_details_no_update_delete BEFORE UPDATE OR DELETE ON reservation_details
FOR EACH ROW EXECUTE FUNCTION reject_ticket_mutation();
CREATE TRIGGER reservation_details_no_truncate BEFORE TRUNCATE ON reservation_details
FOR EACH STATEMENT EXECUTE FUNCTION reject_ticket_mutation();

REVOKE ALL ON TABLE payment_confirmations, reservation_details FROM PUBLIC, ticket_engine_app;
GRANT SELECT, INSERT ON TABLE payment_confirmations, reservation_details TO ticket_engine_app;
