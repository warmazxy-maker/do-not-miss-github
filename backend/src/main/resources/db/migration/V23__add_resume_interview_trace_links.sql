ALTER TABLE resume_interview_evidence_candidates
    ADD COLUMN agent_trace_run_id BIGINT NULL AFTER error_message;

ALTER TABLE resume_interview_score_summaries
    ADD COLUMN scoring_trace_run_id BIGINT NULL AFTER scoring_rule_version;
