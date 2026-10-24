-- B4: a "not relevant" click becomes a candidate evaluation case; an operator reviews it before it
-- can enter the next version of the evaluation set.
CREATE TABLE recommendation_eval_candidates (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    impression_id BIGINT NOT NULL,
    run_id BIGINT,
    user_id VARCHAR(80) NOT NULL,
    event_id BIGINT NOT NULL,
    -- What the student asked for, as recorded with the impression. The reviewer may rewrite it to
    -- remove personal details before approval, because approved cases are committed to the repository.
    need_text VARCHAR(500),
    reason VARCHAR(500),
    status VARCHAR(16) NOT NULL,
    reviewer_note VARCHAR(500),
    reviewed_by VARCHAR(80),
    reviewed_at DATETIME(6),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_rec_eval_candidate_impression UNIQUE (impression_id),
    INDEX idx_rec_eval_candidate_status (status, created_at)
);
