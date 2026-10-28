-- D2: event lifecycle as a state machine.
-- Two independent dimensions:
--   publication (stored, changed only by EventLifecycleService): review_status
--     DRAFT, PENDING_REVIEW, NEEDS_REVISION, APPROVED, OFFLINE (REJECTED kept for later, unused)
--   time (never stored, computed from start_time / end_time): not started / ongoing / ended
-- The old `expired` flag mixed the two and had to be refreshed by a job and by every search; it goes.

ALTER TABLE events
    -- Why an OFFLINE event is offline: ORGANIZER, OPERATOR, ENDED (time ran out), DISCARDED (draft dropped).
    ADD COLUMN offline_reason_code VARCHAR(24) NULL,
    ADD COLUMN offline_reason VARCHAR(300) NULL,
    -- The latest "needs revision" note (AI or operator), shown to the organisation until it resubmits.
    ADD COLUMN revision_note VARCHAR(500) NULL,
    -- How many times the AI review asked for revision; at 3 the next submission goes to a person.
    ADD COLUMN ai_revision_count INT NOT NULL DEFAULT 0,
    -- Who reviews the current submission: AI or MANUAL (operator queue, D3).
    ADD COLUMN review_route VARCHAR(16) NOT NULL DEFAULT 'AI',
    -- Optimistic locking (@Version): a stale edit or decision fails instead of overwriting.
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- Published events that already ended become OFFLINE(ENDED), as the expiry job will do from now on.
UPDATE events
SET review_status = 'OFFLINE', offline_reason_code = 'ENDED', offline_reason = '活动已结束'
WHERE expired = TRUE AND review_status = 'APPROVED';

-- "Rejected" is no longer produced: anything not acceptable needs revision.
UPDATE events SET review_status = 'NEEDS_REVISION' WHERE review_status = 'REJECTED';

DROP INDEX idx_events_expired_end_time ON events;
DROP INDEX idx_events_review_expired_start ON events;
ALTER TABLE events DROP COLUMN expired;
CREATE INDEX idx_events_status_start ON events (review_status, start_time);
CREATE INDEX idx_events_status_end ON events (review_status, end_time);

-- Business timeline of one event: every transition, including those made by the system or the AI.
-- (The D1 audit log answers "who called which protected API"; this answers "what happened to this event".)
CREATE TABLE event_status_history (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id BIGINT NOT NULL,
    from_status VARCHAR(24),
    to_status VARCHAR(24) NOT NULL,
    action VARCHAR(32) NOT NULL,
    -- SYSTEM, AI, ORGANIZER, OPERATOR
    actor_type VARCHAR(16) NOT NULL,
    actor_user_id VARCHAR(80),
    -- Revision notes, offline reasons: shown to the organisation.
    reason VARCHAR(500),
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_event_status_history_event FOREIGN KEY (event_id) REFERENCES events(id),
    INDEX idx_event_status_history_event (event_id, id)
);
