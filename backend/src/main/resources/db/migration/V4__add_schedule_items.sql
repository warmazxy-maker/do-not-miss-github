CREATE TABLE schedule_items (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id VARCHAR(80) NOT NULL,
    item_type VARCHAR(32) NOT NULL,
    source_id BIGINT,
    title VARCHAR(160) NOT NULL,
    start_time DATETIME(6) NOT NULL,
    end_time DATETIME(6) NOT NULL,
    location VARCHAR(160),
    notes VARCHAR(1000),
    status VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    INDEX idx_schedule_user_status_start (user_id, status, start_time),
    INDEX idx_schedule_source (user_id, item_type, source_id)
);

INSERT INTO schedule_items (
    user_id, item_type, source_id, title, start_time, end_time,
    location, notes, status, created_at, updated_at
)
SELECT
    r.user_id,
    'RESERVATION',
    r.id,
    e.title,
    e.start_time,
    DATE_ADD(e.start_time, INTERVAL 2 HOUR),
    e.location,
    e.content,
    'ACTIVE',
    NOW(6),
    NOW(6)
FROM reservations r
JOIN events e ON e.id = r.event_id
WHERE r.status = 'RESERVED';
