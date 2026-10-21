CREATE TABLE guide_sessions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(80) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    current_goal VARCHAR(500),
    session_summary VARCHAR(3000),
    last_action VARCHAR(48),
    last_rag_need VARCHAR(1000),
    recent_messages_json LONGTEXT NOT NULL,
    last_recommended_event_ids_json LONGTEXT NOT NULL,
    accepted_preferences_json LONGTEXT NOT NULL,
    rejected_conditions_json LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_guide_sessions_session UNIQUE (session_id)
);

CREATE INDEX idx_guide_sessions_user_updated ON guide_sessions (user_id, updated_at);
CREATE INDEX idx_guide_sessions_user_expires ON guide_sessions (user_id, expires_at);
