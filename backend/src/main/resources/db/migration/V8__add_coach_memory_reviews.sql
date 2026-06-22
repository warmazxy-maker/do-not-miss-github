CREATE TABLE coach_memory_reviews (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id VARCHAR(80) NOT NULL,
    source_log_id BIGINT NOT NULL,
    memory_type VARCHAR(32) NOT NULL,
    title VARCHAR(160) NOT NULL,
    memory_text VARCHAR(1200) NOT NULL,
    tags VARCHAR(500),
    strength INT NOT NULL,
    review_count INT NOT NULL,
    last_reviewed_at DATETIME(6),
    next_review_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_coach_memory_review_log UNIQUE (user_id, source_log_id),
    CONSTRAINT fk_coach_memory_review_log FOREIGN KEY (source_log_id) REFERENCES coach_logs(id) ON DELETE CASCADE
);

CREATE INDEX idx_coach_memory_reviews_due ON coach_memory_reviews (user_id, next_review_at);
CREATE INDEX idx_coach_memory_reviews_updated ON coach_memory_reviews (user_id, updated_at);
