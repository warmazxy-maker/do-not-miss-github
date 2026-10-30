-- D3: operator console.

-- Spot checks: an operator asks for a few recent AI-approved events to look at again.
-- The event stays published while it is checked; the outcome is PASSED or TAKEN_OFFLINE.
-- One row per event ever drawn, so the same event is not drawn twice.
CREATE TABLE event_spot_checks (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id BIGINT NOT NULL,
    -- PENDING, PASSED, TAKEN_OFFLINE
    status VARCHAR(16) NOT NULL,
    drawn_by_user_id VARCHAR(80) NOT NULL,
    drawn_at DATETIME(6) NOT NULL,
    resolved_by_user_id VARCHAR(80),
    resolved_at DATETIME(6),
    CONSTRAINT fk_event_spot_checks_event FOREIGN KEY (event_id) REFERENCES events(id),
    CONSTRAINT uk_event_spot_checks_event UNIQUE (event_id),
    INDEX idx_event_spot_checks_status (status, drawn_at)
);

-- Red dot on the organisation's "my events": changes made by the AI or an operator after this
-- moment count as unseen. NULL = never opened the page, so everything counts.
ALTER TABLE users ADD COLUMN events_seen_at DATETIME(6) NULL;
