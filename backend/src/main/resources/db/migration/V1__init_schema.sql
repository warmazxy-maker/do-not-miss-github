CREATE TABLE organizations (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(120) NOT NULL,
    type VARCHAR(60) NOT NULL,
    summary VARCHAR(500) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_organizations_name UNIQUE (name)
);

CREATE TABLE events (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    title VARCHAR(120) NOT NULL,
    organization_name VARCHAR(120) NOT NULL,
    category VARCHAR(32) NOT NULL,
    start_time DATETIME(6) NOT NULL,
    location VARCHAR(160) NOT NULL,
    content VARCHAR(2000) NOT NULL,
    benefit_type VARCHAR(32) NOT NULL,
    skill VARCHAR(500),
    money_amount DECIMAL(12, 2),
    created_by_user_id VARCHAR(80) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    INDEX idx_events_category (category),
    INDEX idx_events_start_time (start_time),
    INDEX idx_events_org (organization_name)
);

CREATE TABLE follows (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id VARCHAR(80) NOT NULL,
    organization_name VARCHAR(120) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_follows_user_org UNIQUE (user_id, organization_name),
    INDEX idx_follows_user (user_id)
);

CREATE TABLE reservations (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id VARCHAR(80) NOT NULL,
    event_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    qr_token VARCHAR(120) NOT NULL,
    reserved_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6),
    CONSTRAINT uk_reservations_user_event UNIQUE (user_id, event_id),
    CONSTRAINT fk_reservations_event FOREIGN KEY (event_id) REFERENCES events (id),
    INDEX idx_reservations_user_status (user_id, status),
    INDEX idx_reservations_qr_token (qr_token)
);

CREATE TABLE achievement_records (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id VARCHAR(80) NOT NULL,
    event_id BIGINT NOT NULL,
    event_title VARCHAR(120) NOT NULL,
    organization_name VARCHAR(120) NOT NULL,
    category VARCHAR(32) NOT NULL,
    event_start_time DATETIME(6) NOT NULL,
    location VARCHAR(160) NOT NULL,
    content VARCHAR(2000) NOT NULL,
    benefit_type VARCHAR(32) NOT NULL,
    skill VARCHAR(500),
    money_amount DECIMAL(12, 2),
    completed_at DATETIME(6) NOT NULL,
    did VARCHAR(2000),
    learned VARCHAR(2000),
    INDEX idx_achievement_user_completed (user_id, completed_at),
    INDEX idx_achievement_user_event (user_id, event_id)
);

INSERT INTO organizations (name, type, summary, created_at) VALUES
('Tokyo Bridge NPO', '公益组织', '长期发布社区陪伴、儿童教育和多文化交流相关实践。', NOW(6)),
('Mirai Career Lab', '企业项目', '提供展会运营、职业体验和留学生向项目协助机会。', NOW(6)),
('Waseda Research Hub', '大学研究室', '关注城市生活、学生经验和社会调研项目。', NOW(6)),
('Campus Media Studio', '校内组织', '发布线上内容运营、采访编辑和短视频协作任务。', NOW(6)),
('Kansai Culture Center', '文化机构', '围绕地域文化节、翻译协助和跨文化活动招募学生。', NOW(6));

INSERT INTO events (
    title, organization_name, category, start_time, location, content,
    benefit_type, skill, money_amount, created_by_user_id, created_at
) VALUES
('社区儿童日语阅读陪伴', 'Tokyo Bridge NPO', 'PUBLIC_WELFARE', '2026-06-05 15:00:00', '东京 新宿区',
 '协助社区中心的儿童阅读活动，准备材料、陪伴朗读，并记录活动反馈。', 'SKILL',
 '日语沟通、儿童陪伴、活动记录', NULL, 'seed-social', NOW(6)),
('留学生展会现场运营', 'Mirai Career Lab', 'COMPANY', '2026-06-12 10:30:00', '大阪 梅田',
 '负责签到、引导、问卷收集和会场整理，适合想接触活动执行的学生。', 'BOTH',
 '现场运营、跨文化沟通、团队协作', 8000.00, 'seed-social', NOW(6)),
('城市生活问卷访谈协助', 'Waseda Research Hub', 'RESEARCH', '2026-06-18 13:00:00', '东京 早稻田',
 '协助研究室进行问卷整理、访谈预约和基础资料录入。', 'BOTH',
 '社会调研、访谈整理、数据录入', 3000.00, 'seed-social', NOW(6)),
('线上中文社媒内容运营', 'Campus Media Studio', 'ONLINE', '2026-06-20 18:30:00', '线上',
 '协助整理活动资讯、撰写中文推文，并进行简单数据复盘。', 'SKILL',
 '内容运营、文案写作、数据复盘', NULL, 'seed-social', NOW(6)),
('关西地域文化节翻译协助', 'Kansai Culture Center', 'CULTURE', '2026-06-29 09:30:00', '京都 左京区',
 '在文化节现场协助中日翻译、游客引导和活动记录。', 'BOTH',
 '中日翻译、公共沟通、活动执行', 5000.00, 'seed-social', NOW(6));

INSERT INTO follows (user_id, organization_name, created_at) VALUES
('demo-student', 'Tokyo Bridge NPO', NOW(6)),
('demo-student', 'Mirai Career Lab', NOW(6));

INSERT INTO achievement_records (
    user_id, event_id, event_title, organization_name, category, event_start_time, location,
    content, benefit_type, skill, money_amount, completed_at, did, learned
) VALUES
('demo-student', 1, '社区儿童日语阅读陪伴', 'Tokyo Bridge NPO', 'PUBLIC_WELFARE',
 '2026-05-12 15:00:00', '东京 新宿区',
 '协助社区中心的儿童阅读活动，准备材料、陪伴朗读，并记录活动反馈。', 'SKILL',
 '日语沟通、儿童陪伴、活动记录', NULL, '2026-05-12 18:00:00',
 '负责阅读材料整理、现场引导和儿童朗读陪伴。',
 '更敢用日语沟通，也学会了把复杂说明拆成简单步骤。'),
('demo-student', 2, '留学生展会现场运营', 'Mirai Career Lab', 'COMPANY',
 '2026-05-15 10:30:00', '大阪 梅田',
 '负责签到、引导、问卷收集和会场整理，适合想接触活动执行的学生。', 'BOTH',
 '现场运营、跨文化沟通、团队协作', 8000.00, '2026-05-15 17:00:00',
 '完成签到、问卷回收、会场动线引导，并和团队复盘现场问题。',
 '理解了活动运营的节奏，现场协作和临场沟通能力明显提升。');
