-- F3: goals. A goal is the student's own words about where they want to go; its milestones are
-- platform things (an event or a challenge), and progress follows what really happened there
-- (checked in, challenge completed). At most 3 active goals per student, enforced by the service.
CREATE TABLE tandem_goals (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id VARCHAR(80) NOT NULL,
    title VARCHAR(120) NOT NULL,
    -- What success looks like and why it matters, in the student's words.
    description VARCHAR(500),
    -- OUTCOME (考过 N2), ABILITY (能和日本人正常聊天), HABIT (每周一次线下活动)
    goal_type VARCHAR(16) NOT NULL,
    target_date DATE,
    -- ACTIVE, COMPLETED, ABANDONED; only the student decides the last two
    status VARCHAR(16) NOT NULL,
    last_reviewed_at DATETIME(6),
    last_review_note VARCHAR(300),
    source_run_id BIGINT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    closed_at DATETIME(6),
    INDEX idx_tandem_goals_user (user_id, status)
);

CREATE TABLE tandem_goal_milestones (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    goal_id BIGINT NOT NULL,
    user_id VARCHAR(80) NOT NULL,
    position INT NOT NULL,
    -- EVENT or CHALLENGE
    kind VARCHAR(16) NOT NULL,
    ref_id BIGINT NOT NULL,
    -- Title when it was added, so a milestone still reads well if the event is gone.
    title VARCHAR(160) NOT NULL,
    -- Why this step, e.g. "先在轻松的场合开口".
    note VARCHAR(200),
    -- The goal card created this challenge; rolling the card back deletes it again.
    created_challenge BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL,
    INDEX idx_tandem_goal_milestones_goal (goal_id, position)
);
