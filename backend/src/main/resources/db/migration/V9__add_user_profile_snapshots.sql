CREATE TABLE user_profile_snapshots (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id VARCHAR(80) NOT NULL,
    summary VARCHAR(1200) NOT NULL,
    strengths_json LONGTEXT NOT NULL,
    preferred_categories_json LONGTEXT NOT NULL,
    preferred_locations_json LONGTEXT NOT NULL,
    benefit_preferences_json LONGTEXT NOT NULL,
    evidence_keywords_json LONGTEXT NOT NULL,
    recent_signals_json LONGTEXT NOT NULL,
    completed_count BIGINT NOT NULL,
    active_challenge_count BIGINT NOT NULL,
    coach_log_count BIGINT NOT NULL,
    dirty TINYINT(1) NOT NULL DEFAULT 1,
    generated_by VARCHAR(80) NOT NULL,
    error_message VARCHAR(1000),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_user_profile_snapshots_user UNIQUE (user_id)
);

CREATE INDEX idx_user_profile_snapshots_dirty ON user_profile_snapshots (dirty, updated_at);
