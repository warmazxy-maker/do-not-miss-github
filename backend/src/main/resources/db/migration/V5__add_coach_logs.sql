CREATE TABLE coach_messages (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id VARCHAR(80) NOT NULL,
    role VARCHAR(32) NOT NULL,
    content VARCHAR(3000) NOT NULL,
    message_date DATE NOT NULL,
    created_at DATETIME(6) NOT NULL,
    INDEX idx_coach_messages_user_date (user_id, message_date, created_at)
);

CREATE TABLE coach_logs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id VARCHAR(80) NOT NULL,
    log_date DATE NOT NULL,
    title VARCHAR(120) NOT NULL,
    summary VARCHAR(1000) NOT NULL,
    content VARCHAR(3000) NOT NULL,
    tags VARCHAR(500),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_coach_logs_user_date UNIQUE (user_id, log_date),
    INDEX idx_coach_logs_user_date (user_id, log_date)
);
