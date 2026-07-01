CREATE TABLE agent_trace_artifacts (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    run_id BIGINT NOT NULL,
    step_name VARCHAR(64),
    artifact_type VARCHAR(80) NOT NULL,
    content_summary VARCHAR(1000),
    content_json JSON NOT NULL,
    content_hash CHAR(64) NOT NULL,
    redacted BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL,
    INDEX idx_agent_trace_artifacts_run (run_id, id),
    INDEX idx_agent_trace_artifacts_run_type (run_id, artifact_type),
    CONSTRAINT fk_agent_trace_artifacts_run FOREIGN KEY (run_id) REFERENCES agent_runs(id) ON DELETE CASCADE
);
