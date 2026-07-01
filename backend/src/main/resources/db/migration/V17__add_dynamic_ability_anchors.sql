CREATE TABLE ability_dynamic_anchors (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    normalized_key VARCHAR(120) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    description VARCHAR(600),
    aliases_json JSON NOT NULL,
    member_dimensions_json JSON NOT NULL,
    source VARCHAR(40) NOT NULL,
    status VARCHAR(32) NOT NULL,
    confidence DECIMAL(5,4) NOT NULL DEFAULT 0.7000,
    support_count INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_ability_dynamic_anchor_key UNIQUE (normalized_key)
);

CREATE INDEX idx_ability_dynamic_anchor_status_confidence
    ON ability_dynamic_anchors (status, confidence, updated_at);
