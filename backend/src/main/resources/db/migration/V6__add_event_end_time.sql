ALTER TABLE events
    ADD COLUMN end_time DATETIME(6);

UPDATE events
SET end_time = DATE_ADD(start_time, INTERVAL 2 HOUR)
WHERE end_time IS NULL;

ALTER TABLE events
    MODIFY end_time DATETIME(6) NOT NULL;

CREATE INDEX idx_events_end_time ON events (end_time);
