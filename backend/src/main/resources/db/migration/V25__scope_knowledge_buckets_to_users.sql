-- Function buckets remain global and seeded. Knowledge buckets are personal labels,
-- so the same concept may intentionally have different names for different users.
ALTER TABLE capability_v2_buckets
    ADD COLUMN owner_user_id VARCHAR(80) NOT NULL DEFAULT '__GLOBAL__' AFTER bucket_type;

ALTER TABLE capability_v2_buckets
    DROP INDEX uk_capability_v2_bucket_type_name,
    ADD CONSTRAINT uk_capability_v2_bucket_scope_name
        UNIQUE (bucket_type, owner_user_id, normalized_name);

CREATE INDEX idx_capability_v2_bucket_owner_type_status
    ON capability_v2_buckets (owner_user_id, bucket_type, status, display_name);
