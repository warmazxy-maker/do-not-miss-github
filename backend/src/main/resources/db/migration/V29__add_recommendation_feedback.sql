-- B1: what was recommended to whom (impressions), and how the student reacted (feedback).

CREATE TABLE recommendation_impressions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    -- EVENT_RECOMMENDATION agent run that produced it; links feedback back to the trace.
    run_id BIGINT,
    user_id VARCHAR(80) NOT NULL,
    event_id BIGINT NOT NULL,
    rank_no INT NOT NULL,
    score INT NOT NULL,
    need_text VARCHAR(500),
    shown_at DATETIME(6) NOT NULL,
    INDEX idx_rec_impression_user_event (user_id, event_id, shown_at),
    INDEX idx_rec_impression_run (run_id),
    INDEX idx_rec_impression_shown (shown_at)
);

CREATE TABLE recommendation_feedback (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    impression_id BIGINT NOT NULL,
    user_id VARCHAR(80) NOT NULL,
    event_id BIGINT NOT NULL,
    -- INTERESTED / NOT_RELEVANT are clicked by the student (explicit);
    -- RESERVED / RESERVATION_CANCELLED / COMPLETED / FOLLOWED are inferred from later actions (implicit).
    feedback_type VARCHAR(32) NOT NULL,
    explicit_feedback BOOLEAN NOT NULL,
    reason VARCHAR(500),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_rec_feedback_impression_type UNIQUE (impression_id, feedback_type),
    CONSTRAINT fk_rec_feedback_impression
        FOREIGN KEY (impression_id) REFERENCES recommendation_impressions(id) ON DELETE CASCADE,
    INDEX idx_rec_feedback_user_explicit (user_id, explicit_feedback, event_id),
    INDEX idx_rec_feedback_created (created_at)
);
