CREATE TABLE challenges (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id VARCHAR(80) NOT NULL,
    title VARCHAR(120) NOT NULL,
    category VARCHAR(60) NOT NULL,
    goal VARCHAR(500) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6),
    did VARCHAR(2000),
    learned VARCHAR(2000),
    INDEX idx_challenges_user_status (user_id, status),
    INDEX idx_challenges_user_created (user_id, created_at)
);

ALTER TABLE achievement_records
    ADD COLUMN source_type VARCHAR(32) NOT NULL DEFAULT 'EVENT' AFTER user_id,
    ADD COLUMN source_id BIGINT NOT NULL DEFAULT 0 AFTER source_type,
    MODIFY COLUMN category VARCHAR(60) NOT NULL;

UPDATE achievement_records SET source_id = event_id WHERE source_type = 'EVENT';
UPDATE achievement_records SET category = '公益' WHERE category = 'PUBLIC_WELFARE';
UPDATE achievement_records SET category = '企业' WHERE category = 'COMPANY';
UPDATE achievement_records SET category = '校内' WHERE category = 'CAMPUS';
UPDATE achievement_records SET category = '线上' WHERE category = 'ONLINE';
UPDATE achievement_records SET category = '研究' WHERE category = 'RESEARCH';
UPDATE achievement_records SET category = '文化' WHERE category = 'CULTURE';

CREATE INDEX idx_achievement_source ON achievement_records (user_id, source_type, source_id);

INSERT INTO challenges (
    user_id, title, category, goal, description, status, created_at, completed_at, did, learned
) VALUES
('demo-student', '学完黑马商城项目', '技能挑战', '完成后端接口、前端页面和部署笔记',
 '用 Java 和前端完整走一遍电商项目，重点训练项目结构、接口设计和部署流程。', 'COMPLETED',
 '2026-05-01 09:00:00', '2026-05-20 20:00:00',
 '完成商品、购物车、订单三个核心模块，并整理接口文档。',
 '对 Spring Boot 分层、数据库建模和前后端联调有了整体认识。'),
('demo-student', '英语过六级', '考试挑战', '完成 30 天听力和阅读训练',
 '每天完成听力、阅读和单词复盘，目标是通过大学英语六级。', 'ACTIVE',
 NOW(6), NULL, NULL, NULL);

INSERT INTO achievement_records (
    user_id, source_type, source_id, event_id, event_title, organization_name, category, event_start_time, location,
    content, benefit_type, skill, money_amount, completed_at, did, learned
) VALUES
('demo-student', 'CHALLENGE', 1, 1, '学完黑马商城项目', '个人挑战', '技能挑战',
 '2026-05-01 09:00:00', '自定义',
 '用 Java 和前端完整走一遍电商项目，重点训练项目结构、接口设计和部署流程。', 'SKILL',
 '完成后端接口、前端页面和部署笔记', NULL, '2026-05-20 20:00:00',
 '完成商品、购物车、订单三个核心模块，并整理接口文档。',
 '对 Spring Boot 分层、数据库建模和前后端联调有了整体认识。');
