-- F4: Tandem reaches out. A proactive message is part of the one long conversation (F4 decision 1),
-- so it lives in tandem_messages with a flag, a read time and a hidden note for the model.
ALTER TABLE tandem_messages
    ADD COLUMN proactive BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN read_at DATETIME(6),
    -- What the model is told about this message in later turns (why it was sent, ids involved).
    ADD COLUMN context_note VARCHAR(1000);

-- Things worth telling the student, found by the scanners. One row per thing, ever (dedupe_key),
-- so the same thing is never said twice (decision 4). Rows wait process_after so that things
-- happening close together become one message, and are pushed to the next morning when the
-- daily limit is reached.
CREATE TABLE tandem_wake_triggers (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id VARCHAR(80) NOT NULL,
    -- CHECK_IN, GOAL_STEP, RANK_UP, EVENT_TOMORROW, GOAL_REVIEW
    kind VARCHAR(24) NOT NULL,
    dedupe_key VARCHAR(160) NOT NULL,
    payload_json TEXT NOT NULL,
    -- PENDING, PROCESSING, DONE, SKIPPED, FAILED
    status VARCHAR(16) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    process_after DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    processed_at DATETIME(6),
    message_id BIGINT,
    run_id BIGINT,
    note VARCHAR(300),
    CONSTRAINT uk_tandem_wake_dedupe UNIQUE (user_id, dedupe_key),
    INDEX idx_tandem_wake_due (status, process_after)
);

-- The student's switches (decision 5). No row = everything on.
CREATE TABLE tandem_wake_settings (
    user_id VARCHAR(80) PRIMARY KEY,
    enabled BOOLEAN NOT NULL,
    -- Comma-separated kinds the student turned off.
    disabled_kinds VARCHAR(200) NOT NULL DEFAULT '',
    updated_at DATETIME(6) NOT NULL
);

-- The last capability rank Tandem has seen per bucket; a higher rank than this is a rank-up.
CREATE TABLE tandem_wake_ranks (
    user_id VARCHAR(80) NOT NULL,
    bucket_id BIGINT NOT NULL,
    rank_name VARCHAR(48) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id, bucket_id)
);

-- Where each scanner left off (for example the last achievement record id it looked at).
CREATE TABLE tandem_wake_cursors (
    name VARCHAR(40) PRIMARY KEY,
    position_value BIGINT NOT NULL,
    updated_at DATETIME(6) NOT NULL
);
