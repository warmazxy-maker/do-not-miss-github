CREATE TABLE ability_scoring_profiles (
    user_id VARCHAR(80) PRIMARY KEY,
    profile_confidence DECIMAL(5,4) NOT NULL DEFAULT 0.5000,
    confidence_version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT chk_ability_profile_confidence
        CHECK (profile_confidence >= 0.0000 AND profile_confidence <= 1.0000)
);

CREATE TABLE user_ability_states (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id VARCHAR(80) NOT NULL,
    dimension_name VARCHAR(100) NOT NULL,
    normalized_dimension VARCHAR(100) NOT NULL,
    experience_score DECIMAL(14,4) NOT NULL DEFAULT 0.0000,
    ability_score DECIMAL(7,4) NOT NULL DEFAULT 0.0000,
    ability_uncertainty DECIMAL(5,4) NOT NULL DEFAULT 1.0000,
    rank_name VARCHAR(48) NOT NULL DEFAULT 'UNRATED',
    state_version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_ability_state_user_dimension UNIQUE (user_id, normalized_dimension),
    CONSTRAINT chk_ability_state_experience CHECK (experience_score >= 0.0000),
    CONSTRAINT chk_ability_state_score CHECK (ability_score >= 0.0000 AND ability_score <= 100.0000),
    CONSTRAINT chk_ability_state_uncertainty CHECK (ability_uncertainty >= 0.0000 AND ability_uncertainty <= 1.0000)
);

CREATE INDEX idx_ability_state_user_score
    ON user_ability_states (user_id, ability_score);
CREATE INDEX idx_ability_state_user_updated
    ON user_ability_states (user_id, updated_at);

CREATE TABLE ability_assessment_jobs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    request_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(80) NOT NULL,
    achievement_record_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    evidence_hash CHAR(64) NOT NULL,
    prompt_version VARCHAR(80) NOT NULL,
    rubric_version VARCHAR(80) NOT NULL,
    model_name VARCHAR(120),
    input_snapshot_json JSON NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    error_message VARCHAR(1200),
    supersedes_job_id BIGINT,
    started_at DATETIME(6),
    completed_at DATETIME(6),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_ability_job_request UNIQUE (request_id),
    CONSTRAINT uk_ability_job_evidence_version
        UNIQUE (achievement_record_id, evidence_hash, prompt_version, rubric_version),
    CONSTRAINT fk_ability_job_record
        FOREIGN KEY (achievement_record_id) REFERENCES achievement_records(id) ON DELETE RESTRICT,
    CONSTRAINT fk_ability_job_supersedes
        FOREIGN KEY (supersedes_job_id) REFERENCES ability_assessment_jobs(id) ON DELETE SET NULL
);

CREATE INDEX idx_ability_job_status_created
    ON ability_assessment_jobs (status, created_at);
CREATE INDEX idx_ability_job_user_created
    ON ability_assessment_jobs (user_id, created_at);

CREATE TABLE ability_evidence_assessments (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    job_id BIGINT NOT NULL,
    normalized_activity_type VARCHAR(48) NOT NULL,
    activity_difficulty DECIMAL(7,4) NOT NULL,
    activity_difficulty_confidence DECIMAL(5,4) NOT NULL,
    completion_quality DECIMAL(5,4) NOT NULL,
    completion_quality_confidence DECIMAL(5,4) NOT NULL,
    personal_contribution DECIMAL(5,4) NOT NULL,
    personal_contribution_confidence DECIMAL(5,4) NOT NULL,
    assessment_confidence DECIMAL(5,4) NOT NULL,
    evidence_findings_json JSON NOT NULL,
    novelty_features_json JSON NOT NULL,
    risk_flags_json JSON NOT NULL,
    judge_recommendation_json JSON NOT NULL,
    raw_response_json JSON NOT NULL,
    evidence_hash CHAR(64) NOT NULL,
    prompt_version VARCHAR(80) NOT NULL,
    rubric_version VARCHAR(80) NOT NULL,
    model_name VARCHAR(120) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_ability_evidence_job UNIQUE (job_id),
    CONSTRAINT fk_ability_evidence_job
        FOREIGN KEY (job_id) REFERENCES ability_assessment_jobs(id) ON DELETE CASCADE,
    CONSTRAINT chk_ability_evidence_difficulty
        CHECK (activity_difficulty >= 0.0000 AND activity_difficulty <= 100.0000),
    CONSTRAINT chk_ability_evidence_difficulty_conf
        CHECK (activity_difficulty_confidence >= 0.0000 AND activity_difficulty_confidence <= 1.0000),
    CONSTRAINT chk_ability_evidence_completion
        CHECK (completion_quality >= 0.0000 AND completion_quality <= 1.0000),
    CONSTRAINT chk_ability_evidence_completion_conf
        CHECK (completion_quality_confidence >= 0.0000 AND completion_quality_confidence <= 1.0000),
    CONSTRAINT chk_ability_evidence_contribution
        CHECK (personal_contribution >= 0.0000 AND personal_contribution <= 1.0000),
    CONSTRAINT chk_ability_evidence_contribution_conf
        CHECK (personal_contribution_confidence >= 0.0000 AND personal_contribution_confidence <= 1.0000),
    CONSTRAINT chk_ability_evidence_assessment_conf
        CHECK (assessment_confidence >= 0.0000 AND assessment_confidence <= 1.0000)
);

CREATE INDEX idx_ability_evidence_hash
    ON ability_evidence_assessments (evidence_hash);

CREATE TABLE ability_evidence_dimensions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    assessment_id BIGINT NOT NULL,
    dimension_name VARCHAR(100) NOT NULL,
    normalized_dimension VARCHAR(100) NOT NULL,
    relevance DECIMAL(5,4) NOT NULL,
    relevance_confidence DECIMAL(5,4) NOT NULL,
    claimed_outcome VARCHAR(600),
    evidence_refs_json JSON NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_ability_evidence_dimension
        UNIQUE (assessment_id, normalized_dimension),
    CONSTRAINT fk_ability_dimension_assessment
        FOREIGN KEY (assessment_id) REFERENCES ability_evidence_assessments(id) ON DELETE CASCADE,
    CONSTRAINT chk_ability_dimension_relevance
        CHECK (relevance >= 0.0000 AND relevance <= 1.0000),
    CONSTRAINT chk_ability_dimension_relevance_conf
        CHECK (relevance_confidence >= 0.0000 AND relevance_confidence <= 1.0000)
);

CREATE INDEX idx_ability_dimension_normalized
    ON ability_evidence_dimensions (normalized_dimension);

CREATE TABLE ability_score_results (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    request_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(80) NOT NULL,
    achievement_record_id BIGINT NOT NULL,
    assessment_id BIGINT NOT NULL,
    ability_state_id BIGINT NOT NULL,
    dimension_name VARCHAR(100) NOT NULL,
    normalized_dimension VARCHAR(100) NOT NULL,
    status VARCHAR(32) NOT NULL,
    old_experience_score DECIMAL(14,4) NOT NULL,
    provisional_experience_gain DECIMAL(10,4) NOT NULL,
    verified_experience_gain DECIMAL(10,4) NOT NULL,
    new_experience_score DECIMAL(14,4) NOT NULL,
    old_ability_score DECIMAL(7,4) NOT NULL,
    new_ability_score DECIMAL(7,4) NOT NULL,
    old_ability_uncertainty DECIMAL(5,4) NOT NULL,
    new_ability_uncertainty DECIMAL(5,4) NOT NULL,
    profile_confidence_snapshot DECIMAL(5,4) NOT NULL,
    growth_value DECIMAL(8,6) NOT NULL,
    verification_strength DECIMAL(8,6) NOT NULL,
    profile_confidence_multiplier DECIMAL(6,4) NOT NULL,
    difficulty_match_multiplier DECIMAL(6,4) NOT NULL,
    factor_snapshot_json JSON NOT NULL,
    judge_flags_json JSON NOT NULL,
    scoring_rule_version VARCHAR(80) NOT NULL,
    history_snapshot_version VARCHAR(80) NOT NULL,
    supersedes_result_id BIGINT,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_ability_score_request_dimension
        UNIQUE (request_id, normalized_dimension),
    CONSTRAINT uk_ability_score_assessment_rule
        UNIQUE (assessment_id, normalized_dimension, scoring_rule_version),
    CONSTRAINT fk_ability_score_record
        FOREIGN KEY (achievement_record_id) REFERENCES achievement_records(id) ON DELETE RESTRICT,
    CONSTRAINT fk_ability_score_assessment
        FOREIGN KEY (assessment_id) REFERENCES ability_evidence_assessments(id) ON DELETE RESTRICT,
    CONSTRAINT fk_ability_score_state
        FOREIGN KEY (ability_state_id) REFERENCES user_ability_states(id) ON DELETE RESTRICT,
    CONSTRAINT fk_ability_score_supersedes
        FOREIGN KEY (supersedes_result_id) REFERENCES ability_score_results(id) ON DELETE SET NULL,
    CONSTRAINT chk_ability_score_old_ability
        CHECK (old_ability_score >= 0.0000 AND old_ability_score <= 100.0000),
    CONSTRAINT chk_ability_score_new_ability
        CHECK (new_ability_score >= 0.0000 AND new_ability_score <= 100.0000),
    CONSTRAINT chk_ability_score_profile_conf
        CHECK (profile_confidence_snapshot >= 0.0000 AND profile_confidence_snapshot <= 1.0000)
);

CREATE INDEX idx_ability_score_user_created
    ON ability_score_results (user_id, created_at);
CREATE INDEX idx_ability_score_state_created
    ON ability_score_results (ability_state_id, created_at);
CREATE INDEX idx_ability_score_status_created
    ON ability_score_results (status, created_at);

CREATE TABLE judge_assessments (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    request_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(80) NOT NULL,
    ability_state_id BIGINT NOT NULL,
    score_result_id BIGINT,
    status VARCHAR(32) NOT NULL,
    decision VARCHAR(32) NOT NULL,
    trigger_reasons_json JSON NOT NULL,
    questions_json JSON NOT NULL,
    answers_json JSON NOT NULL,
    rubric_result_json JSON NOT NULL,
    rubric_version VARCHAR(80) NOT NULL,
    judge_model_name VARCHAR(120),
    ability_score_at_trigger DECIMAL(7,4) NOT NULL,
    confidence_before DECIMAL(5,4) NOT NULL,
    proposed_confidence_delta DECIMAL(5,4) NOT NULL DEFAULT 0.0000,
    confidence_after DECIMAL(5,4),
    reviewer_type VARCHAR(32),
    reviewer_id VARCHAR(80),
    review_reason VARCHAR(1000),
    started_at DATETIME(6),
    completed_at DATETIME(6),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_judge_assessment_request UNIQUE (request_id),
    CONSTRAINT fk_judge_ability_state
        FOREIGN KEY (ability_state_id) REFERENCES user_ability_states(id) ON DELETE RESTRICT,
    CONSTRAINT fk_judge_score_result
        FOREIGN KEY (score_result_id) REFERENCES ability_score_results(id) ON DELETE SET NULL,
    CONSTRAINT chk_judge_ability_score
        CHECK (ability_score_at_trigger >= 0.0000 AND ability_score_at_trigger <= 100.0000),
    CONSTRAINT chk_judge_confidence_before
        CHECK (confidence_before >= 0.0000 AND confidence_before <= 1.0000),
    CONSTRAINT chk_judge_confidence_delta
        CHECK (proposed_confidence_delta >= -0.0800 AND proposed_confidence_delta <= 0.0800),
    CONSTRAINT chk_judge_confidence_after
        CHECK (confidence_after IS NULL OR (confidence_after >= 0.0000 AND confidence_after <= 1.0000))
);

CREATE INDEX idx_judge_user_created
    ON judge_assessments (user_id, created_at);
CREATE INDEX idx_judge_status_created
    ON judge_assessments (status, created_at);
CREATE INDEX idx_judge_state_created
    ON judge_assessments (ability_state_id, created_at);
