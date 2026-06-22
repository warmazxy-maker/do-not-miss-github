CREATE TABLE growth_tags (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id VARCHAR(80) NOT NULL,
    name VARCHAR(80) NOT NULL,
    normalized_name VARCHAR(80) NOT NULL,
    description VARCHAR(500),
    score INT NOT NULL,
    evidence_count INT NOT NULL,
    importance_score INT NOT NULL,
    last_updated_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_growth_tags_user_normalized UNIQUE (user_id, normalized_name)
);

CREATE INDEX idx_growth_tags_user_score ON growth_tags (user_id, score);
CREATE INDEX idx_growth_tags_user_updated ON growth_tags (user_id, last_updated_at);

CREATE TABLE growth_tag_evidences (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id VARCHAR(80) NOT NULL,
    tag_id BIGINT NOT NULL,
    record_id BIGINT NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_id BIGINT NOT NULL,
    title VARCHAR(160) NOT NULL,
    summary VARCHAR(800) NOT NULL,
    did VARCHAR(1200),
    learned VARCHAR(1200),
    score_delta INT NOT NULL,
    is_milestone TINYINT(1) NOT NULL DEFAULT 0,
    milestone_reason VARCHAR(500),
    occurred_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_growth_tag_evidence_record UNIQUE (tag_id, record_id),
    CONSTRAINT fk_growth_tag_evidence_tag FOREIGN KEY (tag_id) REFERENCES growth_tags(id) ON DELETE CASCADE,
    CONSTRAINT fk_growth_tag_evidence_record FOREIGN KEY (record_id) REFERENCES achievement_records(id) ON DELETE CASCADE
);

CREATE INDEX idx_growth_tag_evidences_user_time ON growth_tag_evidences (user_id, occurred_at);
CREATE INDEX idx_growth_tag_evidences_tag_time ON growth_tag_evidences (tag_id, occurred_at);
