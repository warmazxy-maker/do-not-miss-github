ALTER TABLE judge_assessments
    ADD COLUMN judge_version BIGINT NOT NULL DEFAULT 0 AFTER review_reason;

CREATE UNIQUE INDEX uk_judge_score_result
    ON judge_assessments (score_result_id);
