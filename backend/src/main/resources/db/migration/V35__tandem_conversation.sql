-- F1: Tandem, the master agent students talk to.

-- One long conversation per student (F1 decision 1). Assistant rows keep what the student saw:
-- the reply, the tool steps (title + range, never raw data) and the notices.
CREATE TABLE tandem_messages (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id VARCHAR(80) NOT NULL,
    -- USER or ASSISTANT
    role VARCHAR(16) NOT NULL,
    content LONGTEXT NOT NULL,
    -- ASSISTANT only: [{"tool","title","detail","status","summary"}]
    steps_json LONGTEXT,
    notices_json LONGTEXT,
    degraded BOOLEAN NOT NULL DEFAULT FALSE,
    run_id BIGINT,
    created_at DATETIME(6) NOT NULL,
    INDEX idx_tandem_messages_user (user_id, id)
);

-- Working memory: a rolling summary of the messages older than the recent window.
-- summarized_up_to = id of the last message folded into the summary.
CREATE TABLE tandem_conversations (
    user_id VARCHAR(80) PRIMARY KEY,
    summary TEXT,
    summarized_up_to BIGINT,
    updated_at DATETIME(6) NOT NULL
);

-- Long-term memory: only key facts the student said themselves, never data a tool can look up
-- (F1 decision 2). At most 50 per student, enforced by the tool.
CREATE TABLE tandem_memories (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id VARCHAR(80) NOT NULL,
    -- PERSONAL, CONSTRAINT, PREFERENCE, INTENTION, COMMITMENT
    category VARCHAR(16) NOT NULL,
    content VARCHAR(300) NOT NULL,
    -- The student's own words this came from, when the model quoted them.
    source_quote VARCHAR(300),
    source_run_id BIGINT,
    valid_until DATE,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    INDEX idx_tandem_memories_user (user_id, updated_at)
);
