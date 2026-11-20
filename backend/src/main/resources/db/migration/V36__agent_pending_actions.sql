-- F2: confirmation cards. A tool that changes the student's data never runs when the model calls
-- it; it becomes one of these rows, and only the student's click runs it, from payload_json.
CREATE TABLE agent_pending_actions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id VARCHAR(80) NOT NULL,
    agent_name VARCHAR(40) NOT NULL,
    -- The agent run that proposed it; Tandem shows the card under the answer of that run.
    run_id BIGINT,
    tool_name VARCHAR(64) NOT NULL,
    tool_version INT NOT NULL,
    -- The frozen arguments the click runs with (no model in between).
    payload_json TEXT NOT NULL,
    -- What the card shows, built from the database when it was proposed.
    card_json TEXT NOT NULL,
    -- PENDING, DONE, FAILED, CANCELLED, EXPIRED, SUPERSEDED, UNDONE
    status VARCHAR(16) NOT NULL,
    result_message VARCHAR(500),
    -- What a rollback needs: ids created and how they looked right after the action.
    undo_json TEXT,
    replaced_by BIGINT,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    decided_at DATETIME(6),
    undone_at DATETIME(6),
    INDEX idx_agent_actions_user_run (user_id, run_id),
    INDEX idx_agent_actions_user_status (user_id, status)
);
