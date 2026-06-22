ALTER TABLE events
    ADD COLUMN expired BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE events
SET expired = TRUE
WHERE end_time < NOW(6);

CREATE INDEX idx_events_expired_end_time ON events (expired, end_time);
