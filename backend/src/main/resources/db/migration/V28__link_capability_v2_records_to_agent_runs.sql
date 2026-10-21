-- Stage 17: one completion is processed by one CAPABILITY_V2_PIPELINE agent run.
-- Every classification audit, evidence link and score audit written by that run points
-- back to it, so a bad case can be followed from any row to the whole pipeline trace.
ALTER TABLE capability_v2_classification_audits
    ADD COLUMN agent_run_id BIGINT NULL,
    ADD INDEX idx_capability_v2_audit_agent_run (agent_run_id);

ALTER TABLE capability_v2_evidence_links
    ADD COLUMN agent_run_id BIGINT NULL,
    ADD INDEX idx_capability_v2_evidence_agent_run (agent_run_id);

ALTER TABLE capability_v2_score_audits
    ADD COLUMN agent_run_id BIGINT NULL,
    ADD INDEX idx_capability_v2_score_agent_run (agent_run_id);
