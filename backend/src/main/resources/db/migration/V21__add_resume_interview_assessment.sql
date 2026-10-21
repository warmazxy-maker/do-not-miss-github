CREATE TABLE resume_interview_sessions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    request_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(80) NOT NULL,
    target_role VARCHAR(160) NOT NULL,
    status VARCHAR(32) NOT NULL,
    active_resume_snapshot_id BIGINT,
    final_score_summary_id BIGINT,
    error_message VARCHAR(1200),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6),
    CONSTRAINT uk_resume_interview_request UNIQUE (request_id)
);

CREATE INDEX idx_resume_interview_user_created
    ON resume_interview_sessions (user_id, created_at);
CREATE INDEX idx_resume_interview_status_updated
    ON resume_interview_sessions (status, updated_at);

CREATE TABLE resume_interview_resume_snapshots (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    resume_hash CHAR(64) NOT NULL,
    raw_resume_text LONGTEXT NOT NULL,
    parsed_resume_json JSON NOT NULL,
    parser_prompt_version VARCHAR(80),
    parser_model_name VARCHAR(120),
    parser_trace_run_id BIGINT,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_resume_snapshot_session
        FOREIGN KEY (session_id) REFERENCES resume_interview_sessions(id) ON DELETE CASCADE
);

CREATE INDEX idx_resume_snapshot_session_created
    ON resume_interview_resume_snapshots (session_id, created_at);
CREATE INDEX idx_resume_snapshot_hash
    ON resume_interview_resume_snapshots (resume_hash);

CREATE TABLE resume_interview_questions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    sequence_no INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    target_dimension VARCHAR(100),
    normalized_dimension VARCHAR(100),
    question_text VARCHAR(1200) NOT NULL,
    evidence_refs_json JSON NOT NULL,
    generation_reason VARCHAR(1000),
    agent_trace_run_id BIGINT,
    created_at DATETIME(6) NOT NULL,
    answered_at DATETIME(6),
    CONSTRAINT uk_resume_question_sequence UNIQUE (session_id, sequence_no),
    CONSTRAINT fk_resume_question_session
        FOREIGN KEY (session_id) REFERENCES resume_interview_sessions(id) ON DELETE CASCADE
);

CREATE INDEX idx_resume_question_session_status
    ON resume_interview_questions (session_id, status);

CREATE TABLE resume_interview_answers (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    question_id BIGINT NOT NULL,
    answer_text LONGTEXT NOT NULL,
    answer_hash CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_resume_answer_question UNIQUE (question_id),
    CONSTRAINT fk_resume_answer_session
        FOREIGN KEY (session_id) REFERENCES resume_interview_sessions(id) ON DELETE CASCADE,
    CONSTRAINT fk_resume_answer_question
        FOREIGN KEY (question_id) REFERENCES resume_interview_questions(id) ON DELETE CASCADE
);

CREATE INDEX idx_resume_answer_session_created
    ON resume_interview_answers (session_id, created_at);

CREATE TABLE resume_interview_evidence_candidates (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    resume_snapshot_id BIGINT,
    question_id BIGINT,
    answer_id BIGINT,
    evidence_kind VARCHAR(32) NOT NULL,
    source_type VARCHAR(48) NOT NULL,
    status VARCHAR(32) NOT NULL,
    evidence_hash CHAR(64) NOT NULL,
    ability_evidence_json JSON NOT NULL,
    ability_assessment_id BIGINT,
    ability_score_result_ids_json JSON NOT NULL,
    error_message VARCHAR(1200),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_resume_evidence_session
        FOREIGN KEY (session_id) REFERENCES resume_interview_sessions(id) ON DELETE CASCADE,
    CONSTRAINT fk_resume_evidence_snapshot
        FOREIGN KEY (resume_snapshot_id) REFERENCES resume_interview_resume_snapshots(id) ON DELETE SET NULL,
    CONSTRAINT fk_resume_evidence_question
        FOREIGN KEY (question_id) REFERENCES resume_interview_questions(id) ON DELETE SET NULL,
    CONSTRAINT fk_resume_evidence_answer
        FOREIGN KEY (answer_id) REFERENCES resume_interview_answers(id) ON DELETE SET NULL
);

CREATE INDEX idx_resume_evidence_session_status
    ON resume_interview_evidence_candidates (session_id, status);
CREATE INDEX idx_resume_evidence_hash
    ON resume_interview_evidence_candidates (evidence_hash);

CREATE TABLE resume_interview_score_summaries (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    ability_score_result_ids_json JSON NOT NULL,
    score_summary_json JSON NOT NULL,
    judge_triggered_json JSON NOT NULL,
    scoring_rule_version VARCHAR(80),
    error_message VARCHAR(1200),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6),
    CONSTRAINT uk_resume_score_session UNIQUE (session_id),
    CONSTRAINT fk_resume_score_session
        FOREIGN KEY (session_id) REFERENCES resume_interview_sessions(id) ON DELETE CASCADE
);

CREATE INDEX idx_resume_score_status_updated
    ON resume_interview_score_summaries (status, updated_at);

ALTER TABLE resume_interview_sessions
    ADD CONSTRAINT fk_resume_session_active_snapshot
        FOREIGN KEY (active_resume_snapshot_id) REFERENCES resume_interview_resume_snapshots(id) ON DELETE SET NULL;

ALTER TABLE resume_interview_sessions
    ADD CONSTRAINT fk_resume_session_final_score
        FOREIGN KEY (final_score_summary_id) REFERENCES resume_interview_score_summaries(id) ON DELETE SET NULL;
