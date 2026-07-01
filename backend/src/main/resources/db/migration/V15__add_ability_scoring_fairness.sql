ALTER TABLE ability_assessment_jobs
    ADD COLUMN content_fingerprint CHAR(64) NULL AFTER evidence_hash,
    ADD COLUMN duplicate_of_job_id BIGINT NULL AFTER content_fingerprint,
    ADD COLUMN fairness_status VARCHAR(32) NOT NULL DEFAULT 'CLEAR' AFTER status,
    ADD CONSTRAINT fk_ability_job_duplicate
        FOREIGN KEY (duplicate_of_job_id) REFERENCES ability_assessment_jobs(id) ON DELETE SET NULL;

CREATE INDEX idx_ability_job_user_fingerprint
    ON ability_assessment_jobs (user_id, content_fingerprint);
CREATE INDEX idx_ability_job_fairness_created
    ON ability_assessment_jobs (fairness_status, created_at);

CREATE TABLE ability_score_appeals (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    request_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(80) NOT NULL,
    request_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    score_result_id BIGINT NOT NULL,
    replacement_assessment_id BIGINT,
    normalized_dimension VARCHAR(100) NOT NULL,
    reason VARCHAR(1200) NOT NULL,
    evidence_note VARCHAR(2000),
    resolution VARCHAR(2000),
    reviewer_id VARCHAR(80),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    resolved_at DATETIME(6),
    CONSTRAINT uk_ability_appeal_request UNIQUE (request_id),
    CONSTRAINT uk_ability_appeal_replacement
        UNIQUE (score_result_id, replacement_assessment_id, normalized_dimension, request_type),
    CONSTRAINT fk_ability_appeal_score_result
        FOREIGN KEY (score_result_id) REFERENCES ability_score_results(id) ON DELETE RESTRICT,
    CONSTRAINT fk_ability_appeal_replacement_assessment
        FOREIGN KEY (replacement_assessment_id) REFERENCES ability_evidence_assessments(id) ON DELETE SET NULL
);

CREATE INDEX idx_ability_appeal_user_created
    ON ability_score_appeals (user_id, created_at);
CREATE INDEX idx_ability_appeal_status_created
    ON ability_score_appeals (status, created_at);
