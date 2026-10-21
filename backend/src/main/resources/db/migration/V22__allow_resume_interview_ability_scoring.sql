ALTER TABLE ability_assessment_jobs
    MODIFY achievement_record_id BIGINT NULL;

ALTER TABLE ability_score_results
    MODIFY achievement_record_id BIGINT NULL;
