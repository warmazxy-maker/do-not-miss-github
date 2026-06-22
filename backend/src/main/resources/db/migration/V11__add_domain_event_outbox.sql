CREATE TABLE domain_event_outbox (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    exchange_name VARCHAR(160) NOT NULL,
    routing_key VARCHAR(160) NOT NULL,
    payload_type VARCHAR(255) NOT NULL,
    payload_json JSON NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    last_error VARCHAR(1000),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    sent_at DATETIME(6)
);

CREATE INDEX idx_domain_event_outbox_status_next ON domain_event_outbox (status, next_attempt_at, id);
CREATE INDEX idx_domain_event_outbox_created ON domain_event_outbox (created_at);
