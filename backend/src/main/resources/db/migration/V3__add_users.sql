CREATE TABLE users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    public_user_id VARCHAR(80) NOT NULL,
    username VARCHAR(60) NOT NULL,
    email VARCHAR(120) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_users_public_user_id UNIQUE (public_user_id),
    CONSTRAINT uk_users_username UNIQUE (username),
    CONSTRAINT uk_users_email UNIQUE (email)
);

-- demo password: demo123456
INSERT INTO users (public_user_id, username, email, password_hash, role, created_at) VALUES
('demo-student', 'demo_student', 'student@example.com', '{plain}demo123456', 'STUDENT', NOW(6)),
('demo-social', 'demo_social', 'social@example.com', '{plain}demo123456', 'SOCIAL', NOW(6));
