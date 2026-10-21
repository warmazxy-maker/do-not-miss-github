CREATE TABLE capability_v2_buckets (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    bucket_type VARCHAR(24) NOT NULL,
    normalized_name VARCHAR(120) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    domain_name VARCHAR(120),
    group_name VARCHAR(120),
    description VARCHAR(1200),
    aliases_json JSON NOT NULL,
    source VARCHAR(40) NOT NULL,
    status VARCHAR(32) NOT NULL,
    seed_version VARCHAR(48),
    classification_confidence DECIMAL(5,4) NOT NULL DEFAULT 1.0000,
    support_count INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_capability_v2_bucket_type_name UNIQUE (bucket_type, normalized_name),
    CONSTRAINT chk_capability_v2_bucket_confidence
        CHECK (classification_confidence >= 0.0000 AND classification_confidence <= 1.0000)
);

CREATE INDEX idx_capability_v2_bucket_type_status
    ON capability_v2_buckets (bucket_type, status, display_name);
CREATE INDEX idx_capability_v2_bucket_domain
    ON capability_v2_buckets (domain_name, group_name);

CREATE TABLE user_capability_v2_states (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id VARCHAR(80) NOT NULL,
    bucket_id BIGINT NOT NULL,
    experience_score DECIMAL(14,4) NOT NULL DEFAULT 0.0000,
    ability_score DECIMAL(7,4) NOT NULL DEFAULT 0.0000,
    ability_uncertainty DECIMAL(5,4) NOT NULL DEFAULT 1.0000,
    rank_name VARCHAR(48) NOT NULL DEFAULT 'UNRATED',
    evidence_count INT NOT NULL DEFAULT 0,
    state_version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_user_capability_v2_state UNIQUE (user_id, bucket_id),
    CONSTRAINT fk_user_capability_v2_state_bucket
        FOREIGN KEY (bucket_id) REFERENCES capability_v2_buckets(id) ON DELETE RESTRICT,
    CONSTRAINT chk_user_capability_v2_score
        CHECK (ability_score >= 0.0000 AND ability_score <= 100.0000),
    CONSTRAINT chk_user_capability_v2_uncertainty
        CHECK (ability_uncertainty >= 0.0000 AND ability_uncertainty <= 1.0000)
);

CREATE INDEX idx_user_capability_v2_state_user_score
    ON user_capability_v2_states (user_id, ability_score);
CREATE INDEX idx_user_capability_v2_state_bucket
    ON user_capability_v2_states (bucket_id, updated_at);

CREATE TABLE capability_v2_evidence_links (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id VARCHAR(80) NOT NULL,
    bucket_id BIGINT NOT NULL,
    user_state_id BIGINT,
    source_type VARCHAR(40) NOT NULL,
    source_id VARCHAR(120) NOT NULL,
    evidence_hash CHAR(64),
    legacy_assessment_id BIGINT,
    legacy_dimension_id BIGINT,
    role_name VARCHAR(24) NOT NULL DEFAULT 'NONE',
    relevance DECIMAL(5,4) NOT NULL,
    relevance_confidence DECIMAL(5,4) NOT NULL,
    claimed_outcome VARCHAR(1200),
    evidence_findings_json JSON NOT NULL,
    status VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(220) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_capability_v2_evidence_bucket_key UNIQUE (bucket_id, idempotency_key),
    CONSTRAINT fk_capability_v2_evidence_bucket
        FOREIGN KEY (bucket_id) REFERENCES capability_v2_buckets(id) ON DELETE RESTRICT,
    CONSTRAINT fk_capability_v2_evidence_state
        FOREIGN KEY (user_state_id) REFERENCES user_capability_v2_states(id) ON DELETE SET NULL,
    CONSTRAINT chk_capability_v2_evidence_relevance
        CHECK (relevance >= 0.0000 AND relevance <= 1.0000),
    CONSTRAINT chk_capability_v2_evidence_confidence
        CHECK (relevance_confidence >= 0.0000 AND relevance_confidence <= 1.0000)
);

CREATE INDEX idx_capability_v2_evidence_user_created
    ON capability_v2_evidence_links (user_id, created_at);
CREATE INDEX idx_capability_v2_evidence_source
    ON capability_v2_evidence_links (source_type, source_id);
CREATE INDEX idx_capability_v2_evidence_status
    ON capability_v2_evidence_links (status, created_at);

CREATE TABLE capability_v2_classification_audits (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    request_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(80) NOT NULL,
    source_type VARCHAR(40) NOT NULL,
    source_id VARCHAR(120) NOT NULL,
    evidence_hash CHAR(64),
    schema_version VARCHAR(64) NOT NULL,
    prompt_version VARCHAR(80),
    model_name VARCHAR(120),
    input_snapshot_json JSON NOT NULL,
    knowledge_candidates_json JSON NOT NULL,
    function_candidates_json JSON NOT NULL,
    output_snapshot_json JSON,
    status VARCHAR(32) NOT NULL,
    error_message VARCHAR(1200),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_capability_v2_audit_request UNIQUE (request_id)
);

CREATE INDEX idx_capability_v2_audit_user_created
    ON capability_v2_classification_audits (user_id, created_at);
CREATE INDEX idx_capability_v2_audit_source
    ON capability_v2_classification_audits (source_type, source_id);
CREATE INDEX idx_capability_v2_audit_status
    ON capability_v2_classification_audits (status, created_at);

CREATE TABLE capability_v2_bucket_merge_records (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    source_bucket_id BIGINT NOT NULL,
    target_bucket_id BIGINT NOT NULL,
    merge_type VARCHAR(32) NOT NULL,
    decision_source VARCHAR(40) NOT NULL,
    reason VARCHAR(1200),
    snapshot_json JSON NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_capability_v2_merge_source
        FOREIGN KEY (source_bucket_id) REFERENCES capability_v2_buckets(id) ON DELETE RESTRICT,
    CONSTRAINT fk_capability_v2_merge_target
        FOREIGN KEY (target_bucket_id) REFERENCES capability_v2_buckets(id) ON DELETE RESTRICT
);

CREATE INDEX idx_capability_v2_merge_source
    ON capability_v2_bucket_merge_records (source_bucket_id, created_at);
CREATE INDEX idx_capability_v2_merge_target
    ON capability_v2_bucket_merge_records (target_bucket_id, created_at);

CREATE TABLE capability_v2_score_audits (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id VARCHAR(80) NOT NULL,
    bucket_id BIGINT NOT NULL,
    user_state_id BIGINT,
    evidence_link_id BIGINT NOT NULL,
    legacy_score_result_id BIGINT,
    scoring_rule_version VARCHAR(80) NOT NULL,
    status VARCHAR(32) NOT NULL,
    old_experience_score DECIMAL(14,4),
    experience_gain DECIMAL(10,4),
    new_experience_score DECIMAL(14,4),
    old_ability_score DECIMAL(7,4),
    new_ability_score DECIMAL(7,4),
    old_ability_uncertainty DECIMAL(5,4),
    new_ability_uncertainty DECIMAL(5,4),
    factor_snapshot_json JSON NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_capability_v2_score_evidence_rule UNIQUE (evidence_link_id, scoring_rule_version),
    CONSTRAINT fk_capability_v2_score_bucket
        FOREIGN KEY (bucket_id) REFERENCES capability_v2_buckets(id) ON DELETE RESTRICT,
    CONSTRAINT fk_capability_v2_score_state
        FOREIGN KEY (user_state_id) REFERENCES user_capability_v2_states(id) ON DELETE SET NULL,
    CONSTRAINT fk_capability_v2_score_evidence
        FOREIGN KEY (evidence_link_id) REFERENCES capability_v2_evidence_links(id) ON DELETE RESTRICT
);

CREATE INDEX idx_capability_v2_score_user_created
    ON capability_v2_score_audits (user_id, created_at);
CREATE INDEX idx_capability_v2_score_bucket_created
    ON capability_v2_score_audits (bucket_id, created_at);
