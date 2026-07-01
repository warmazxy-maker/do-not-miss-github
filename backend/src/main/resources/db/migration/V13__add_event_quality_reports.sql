ALTER TABLE events
    ADD COLUMN review_status VARCHAR(32) NOT NULL DEFAULT 'APPROVED';

CREATE INDEX idx_events_review_expired_start ON events (review_status, expired, start_time);

CREATE TABLE event_quality_reports (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id BIGINT NOT NULL,
    quality_score INT NOT NULL,
    quality_level VARCHAR(32) NOT NULL,
    review_suggestion VARCHAR(32) NOT NULL,
    difficulty VARCHAR(32) NOT NULL,
    summary VARCHAR(800),
    target_students_json JSON NOT NULL,
    prerequisites_json JSON NOT NULL,
    learning_outcomes_json JSON NOT NULL,
    extracted_tags_json JSON NOT NULL,
    ability_impacts_json JSON NOT NULL,
    risk_flags_json JSON NOT NULL,
    missing_fields_json JSON NOT NULL,
    duplicate_event_ids_json JSON NOT NULL,
    model_name VARCHAR(120) NOT NULL,
    confidence DOUBLE NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_event_quality_reports_event UNIQUE (event_id),
    CONSTRAINT fk_event_quality_reports_event FOREIGN KEY (event_id) REFERENCES events (id) ON DELETE CASCADE
);

CREATE INDEX idx_event_quality_score ON event_quality_reports (quality_score);
CREATE INDEX idx_event_quality_suggestion ON event_quality_reports (review_suggestion);
