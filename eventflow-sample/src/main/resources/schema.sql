-- POC: Event Store for Strategy B (store-and-process)
CREATE TABLE IF NOT EXISTS event_store (
    id               VARCHAR(36) PRIMARY KEY,
    route_key        VARCHAR(100) NOT NULL,
    envelope_json    TEXT NOT NULL,
    parent_trace_id  VARCHAR(32),
    parent_span_id   VARCHAR(16),
    send_timestamp   BIGINT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at     TIMESTAMPTZ,
    status           VARCHAR(20) NOT NULL DEFAULT 'PENDING'
);

CREATE INDEX IF NOT EXISTS idx_event_store_pending
    ON event_store(status, created_at)
    WHERE status = 'PENDING';
