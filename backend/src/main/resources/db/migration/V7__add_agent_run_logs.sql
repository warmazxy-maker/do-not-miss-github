CREATE TABLE agent_runs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id VARCHAR(80) NOT NULL,
    run_type VARCHAR(48) NOT NULL,
    status VARCHAR(32) NOT NULL,
    goal VARCHAR(500) NOT NULL,
    input_summary VARCHAR(1000),
    output_summary VARCHAR(1000),
    error_message VARCHAR(1000),
    started_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    INDEX idx_agent_runs_user_started (user_id, started_at),
    INDEX idx_agent_runs_user_type (user_id, run_type, started_at)
);

CREATE TABLE agent_run_steps (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    run_id BIGINT NOT NULL,
    sequence_no INT NOT NULL,
    step_name VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    input_summary VARCHAR(1200),
    output_summary VARCHAR(1200),
    error_message VARCHAR(1000),
    started_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    INDEX idx_agent_run_steps_run_seq (run_id, sequence_no),
    CONSTRAINT fk_agent_run_steps_run FOREIGN KEY (run_id) REFERENCES agent_runs(id) ON DELETE CASCADE
);
