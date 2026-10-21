-- A held V2 rank promotion is unlocked by a short, evidence-grounded review.
-- open_key is "userId:bucketId" while a review is IN_PROGRESS and NULL afterwards;
-- MySQL allows many NULLs in a unique index, so this enforces "at most one open
-- review per user bucket" without blocking the history of completed reviews.
CREATE TABLE capability_v2_promotion_reviews (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id VARCHAR(80) NOT NULL,
    bucket_id BIGINT NOT NULL,
    user_state_id BIGINT,
    trigger_evidence_link_id BIGINT,
    from_rank VARCHAR(48) NOT NULL,
    target_rank VARCHAR(48) NOT NULL,
    ability_score_at_start DECIMAL(7,4) NOT NULL,
    status VARCHAR(32) NOT NULL,
    decision VARCHAR(32) NOT NULL,
    model_evaluated BOOLEAN NOT NULL DEFAULT FALSE,
    applied_rank VARCHAR(48),
    questions_json JSON NOT NULL,
    answers_json JSON NOT NULL,
    rubric_json JSON NOT NULL,
    confidence_before DECIMAL(5,4),
    confidence_delta DECIMAL(5,4),
    open_key VARCHAR(140),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6),
    CONSTRAINT uk_capability_v2_promotion_review_open UNIQUE (open_key),
    CONSTRAINT fk_capability_v2_review_bucket
        FOREIGN KEY (bucket_id) REFERENCES capability_v2_buckets(id) ON DELETE RESTRICT,
    CONSTRAINT fk_capability_v2_review_state
        FOREIGN KEY (user_state_id) REFERENCES user_capability_v2_states(id) ON DELETE SET NULL,
    CONSTRAINT fk_capability_v2_review_evidence
        FOREIGN KEY (trigger_evidence_link_id) REFERENCES capability_v2_evidence_links(id) ON DELETE SET NULL
);

CREATE INDEX idx_capability_v2_review_user_bucket
    ON capability_v2_promotion_reviews (user_id, bucket_id, created_at);
