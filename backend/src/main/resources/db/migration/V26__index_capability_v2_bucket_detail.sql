-- Stage 15 bucket detail reads one user's evidence and audits for one bucket.
-- Function buckets are global, so bucket_id alone would scan every user's rows.
CREATE INDEX idx_capability_v2_evidence_user_bucket
    ON capability_v2_evidence_links (user_id, bucket_id, created_at);

CREATE INDEX idx_capability_v2_score_user_bucket
    ON capability_v2_score_audits (user_id, bucket_id, created_at);
