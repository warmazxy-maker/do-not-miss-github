-- D1: role-based access control.
-- Permissions are defined in code (Permission enum, checked by @RequirePermission); roles are data,
-- so a new role is a few rows, not a code change. Each user has exactly one role.

CREATE TABLE roles (
    code VARCHAR(32) PRIMARY KEY,
    name VARCHAR(60) NOT NULL,
    description VARCHAR(300),
    -- Only these can be chosen on the public registration form.
    self_registrable BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL
);

CREATE TABLE role_permissions (
    role_code VARCHAR(32) NOT NULL,
    permission VARCHAR(48) NOT NULL,
    PRIMARY KEY (role_code, permission),
    CONSTRAINT fk_role_permissions_role FOREIGN KEY (role_code) REFERENCES roles(code) ON DELETE CASCADE
);

INSERT INTO roles (code, name, description, self_registrable, created_at) VALUES
    ('STUDENT', '学生', '使用推荐、预约、挑战、教练、简历定段、能力标签等学生功能', TRUE, NOW(6)),
    ('SOCIAL', '组织', '以所属组织的名义发布和管理活动', TRUE, NOW(6)),
    ('OPERATOR', '运营', '审核活动与评测候选，不能进入运维面板', FALSE, NOW(6)),
    ('ADMIN', '管理员', '运维面板、运维操作、审计日志，以及运营的全部权限', FALSE, NOW(6)),
    ('ADMIN_ZERO', '超级管理员', '管理员的全部权限，外加授予角色（暂不开放接口）；只能由后台指定', FALSE, NOW(6));

INSERT INTO role_permissions (role_code, permission) VALUES
    ('STUDENT', 'STUDENT_FEATURES'),
    ('SOCIAL', 'EVENT_PUBLISH'),
    ('OPERATOR', 'EVENT_REVIEW'),
    ('OPERATOR', 'EVAL_CANDIDATE_REVIEW'),
    ('ADMIN', 'EVENT_REVIEW'),
    ('ADMIN', 'EVAL_CANDIDATE_REVIEW'),
    ('ADMIN', 'OPS_CONSOLE_VIEW'),
    ('ADMIN', 'OPS_ACTION'),
    ('ADMIN', 'AUDIT_VIEW'),
    ('ADMIN_ZERO', 'EVENT_REVIEW'),
    ('ADMIN_ZERO', 'EVAL_CANDIDATE_REVIEW'),
    ('ADMIN_ZERO', 'OPS_CONSOLE_VIEW'),
    ('ADMIN_ZERO', 'OPS_ACTION'),
    ('ADMIN_ZERO', 'AUDIT_VIEW'),
    ('ADMIN_ZERO', 'ROLE_ASSIGN');

-- Existing users keep their role (STUDENT / SOCIAL); from now on a role must exist in roles.
ALTER TABLE users
    ADD CONSTRAINT fk_users_role FOREIGN KEY (role) REFERENCES roles(code);

-- Organisation membership: one organisation can have several accounts.
-- Design gap (kept on purpose, see docs): membership is recorded, not verified. Publishing under an
-- organisation the account is not yet a member of auto-joins it. A real check (invitation,
-- operator approval) plugs into OrganizationMembershipService.ensureCanPublish.
CREATE TABLE organization_members (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    organization_id BIGINT NOT NULL,
    user_id VARCHAR(80) NOT NULL,
    -- How the membership came about: INFERRED (from past events), CLAIMED (first to publish), AUTO_JOINED.
    source VARCHAR(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_org_member UNIQUE (organization_id, user_id),
    CONSTRAINT fk_org_member_org FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE CASCADE,
    INDEX idx_org_member_user (user_id)
);

-- Infer membership from who already published under which organisation name (organisation accounts only).
INSERT INTO organization_members (organization_id, user_id, source, created_at)
SELECT DISTINCT o.id, e.created_by_user_id, 'INFERRED', NOW(6)
FROM events e
JOIN organizations o ON o.name = e.organization_name
JOIN users u ON u.public_user_id = e.created_by_user_id AND u.role = 'SOCIAL';

-- Who did what with a permission, including refused attempts.
CREATE TABLE audit_logs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id VARCHAR(80),
    role_code VARCHAR(32),
    permission VARCHAR(48),
    action VARCHAR(120) NOT NULL,
    http_method VARCHAR(10),
    path VARCHAR(300),
    -- ALLOWED: succeeded; FAILED: permitted but the operation threw; DENIED: refused by the permission check.
    outcome VARCHAR(16) NOT NULL,
    detail VARCHAR(500),
    created_at DATETIME(6) NOT NULL,
    INDEX idx_audit_created (created_at),
    INDEX idx_audit_user (user_id, created_at),
    INDEX idx_audit_outcome (outcome, created_at)
);
