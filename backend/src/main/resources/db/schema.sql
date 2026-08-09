-- AI Workbench MySQL schema
-- 由 Spring Boot sql.init 在启动时执行(使用 IF NOT EXISTS,幂等)

CREATE TABLE IF NOT EXISTS users (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    username    VARCHAR(64)  NOT NULL,
    email       VARCHAR(128) NOT NULL,
    password    VARCHAR(128) NOT NULL,
    role        VARCHAR(16)  NOT NULL DEFAULT 'user',
    status      TINYINT      NOT NULL DEFAULT 1,
    -- Phase 1 per-user uid 隔离:uid = uidBase + user.id,确定性分配;老用户为 NULL,登录时回填
    uid         INT          NULL,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_username (username),
    UNIQUE KEY uk_users_email (email),
    UNIQUE KEY uk_users_uid (uid),
    KEY idx_users_created (created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Phase 1 迁移:为已存在的 users 表补 uid 列与唯一索引。
-- 幂等性:依赖 spring.sql.init.continue-on-error=true,重复列(1060)/重复索引(1061)错误被忽略;
-- 新库由上面 CREATE TABLE 已建好 uid 列与索引,这两条 ALTER 报错忽略即可。
ALTER TABLE users ADD COLUMN uid INT NULL;
ALTER TABLE users ADD UNIQUE INDEX uk_users_uid (uid);

CREATE TABLE IF NOT EXISTS chat_session (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    user_id             BIGINT       NOT NULL,
    opencode_session_id VARCHAR(128) NOT NULL,
    title               VARCHAR(255) NOT NULL DEFAULT 'New Chat',
    agent               VARCHAR(64)  NOT NULL DEFAULT 'chat',
    provider_id         VARCHAR(64)  NULL,
    model_id            VARCHAR(128) NULL,
    archived            TINYINT      NOT NULL DEFAULT 0,
    pinned              TINYINT      NOT NULL DEFAULT 0,
    created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_session_opencode (opencode_session_id),
    KEY idx_session_user (user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 会话置顶:为已存在的 chat_session 表补 pinned 列(幂等,重复列 1060 错误被 continue-on-error 忽略)
ALTER TABLE chat_session ADD COLUMN pinned TINYINT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS chat_message (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    session_id          BIGINT       NOT NULL,
    user_id             BIGINT       NOT NULL,
    opencode_message_id VARCHAR(128) NULL,
    role                VARCHAR(16)  NOT NULL,
    content             MEDIUMTEXT   NULL,
    parts               JSON         NULL,
    status              VARCHAR(16)  NOT NULL DEFAULT 'completed',
    created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_msg_session (session_id, id),
    KEY idx_msg_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS provider_config (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    provider_id  VARCHAR(64)  NOT NULL,
    name         VARCHAR(128) NOT NULL,
    base_url     VARCHAR(512) NULL,
    npm          VARCHAR(128) NULL,
    api_key      VARCHAR(512) NULL,
    env_var_name VARCHAR(64)  NOT NULL,
    enabled      TINYINT      NOT NULL DEFAULT 1,
    sort_order   INT          NOT NULL DEFAULT 0,
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_provider_id (provider_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS model_config (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    provider_id VARCHAR(64)  NOT NULL,
    model_id    VARCHAR(128) NOT NULL,
    name        VARCHAR(128) NOT NULL,
    enabled     TINYINT      NOT NULL DEFAULT 1,
    sort_order  INT          NOT NULL DEFAULT 0,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_model (provider_id, model_id),
    KEY idx_model_provider (provider_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS audit_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NULL,
    username    VARCHAR(64)  NULL,
    action      VARCHAR(64)  NOT NULL,
    target      VARCHAR(128) NULL,
    ip          VARCHAR(64)  NULL,
    method      VARCHAR(8)   NULL,
    path        VARCHAR(255) NULL,
    status      VARCHAR(16)  NOT NULL,
    cost_ms     BIGINT       NULL,
    error_msg   VARCHAR(1000) NULL,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_audit_user (user_id, created_at),
    KEY idx_audit_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 提示词模板库:user_id NULL 表示系统内置(全员只读),否则为个人自建模板
CREATE TABLE IF NOT EXISTS prompt_template (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NULL,
    title       VARCHAR(128) NOT NULL,
    category    VARCHAR(32)  NOT NULL DEFAULT 'other',
    content     TEXT         NOT NULL,
    sort_order  INT          NOT NULL DEFAULT 0,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_pt_user (user_id),
    KEY idx_pt_category (category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 内置模板种子(幂等:重复主键冲突被 continue-on-error 忽略)
INSERT IGNORE INTO prompt_template (id, user_id, title, category, content, sort_order) VALUES
  (1, NULL, '写周报', '办公', '请帮我把我的工作内容整理成一份周报,包含:本周完成、下周计划、风险与求助三部分。下面是这周我做的事情:\n\n', 10),
  (2, NULL, '会议纪要', '办公', '请把下面这段讨论整理成会议纪要,包含:会议主题、参会人、讨论要点、结论、待办事项(注明负责人与截止时间)。\n\n', 20),
  (3, NULL, '写邮件', '办公', '请帮我写一封邮件。我会告诉你收件人、目的和要点,请用专业得体的语气组织成完整邮件,主题清晰、正文分段、结尾礼貌。\n\n', 30),
  (4, NULL, '总结文档', '办公', '请总结我上传的文档:①核心要点 ②关键数据/结论 ③需要我关注的行动项。保留原始信息,不要编造内容。\n\n', 40),
  (5, NULL, '数据分析', '办公', '我会给你一份数据文件,请帮我分析并输出:①关键指标 ②主要趋势与结论 ③值得关注的风险点。分析要条理清晰,便于汇报。\n\n', 50),
  (6, NULL, '调试代码', '开发', '我有一段代码出了问题,请帮我:先定位报错根因,再给出修复方案,最后给出修改后的完整代码。下面是我的代码和报错:\n\n', 60),
  (7, NULL, '解读报错', '开发', '下面是我的报错信息,请解读:①发生了什么 ②可能的原因 ③具体的修复步骤。回答请通俗易懂,分步说明。\n\n', 70),
  (8, NULL, '代码审查', '开发', '请审查下面这段代码:指出潜在 bug、性能问题、可读性问题,并给出改进建议和修改后的代码。\n\n', 80),
  (9, NULL, 'SQL 优化', '开发', '请优化下面这条 SQL:分析执行计划中的潜在问题(如全表扫描、索引缺失、隐式转换),给出优化后的 SQL 和建索引建议。\n\n', 90),
  (10, NULL, '需求拆解', '开发', '请把下面这个需求拆解成可执行的开发任务:包括模块划分、接口设计要点、表结构建议、测试要点。\n\n', 100),
  (11, NULL, '头脑风暴', '其他', '请围绕我下面提出的问题做头脑风暴:给出多个有创意的方案,并从可行性、成本、效果三个维度做简要点评,最后给一个推荐。\n\n', 110),
  (12, NULL, '翻译润色', '其他', '请把下面的文字翻译成[目标语言],注意保持语气自然、专业。如有更好的表达请同时给出润色建议。\n\n', 120);

-- 技能广场:用户发布技能到广场供他人安装
CREATE TABLE IF NOT EXISTS skill_marketplace (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    user_id       BIGINT       NOT NULL COMMENT '发布者',
    author_name   VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '发布者用户名(冗余)',
    name          VARCHAR(64)  NOT NULL COMMENT '技能名(小写kebab-case,与 SKILL.md name 一致)',
    description   VARCHAR(1024) NOT NULL DEFAULT '' COMMENT '一句话描述',
    content       MEDIUMTEXT   NOT NULL COMMENT 'SKILL.md 全文',
    installs      INT          NOT NULL DEFAULT 0 COMMENT '被安装次数',
    category      VARCHAR(32)  NOT NULL DEFAULT 'other' COMMENT '分类标签(dev/doc/data/writing/office/other)',
    icon          VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '展示图标(emoji 或短语)',
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_sm_name (name),
    KEY idx_sm_user (user_id),
    KEY idx_sm_installs (installs),
    KEY idx_sm_category (category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 技能广场分类/图标:为已存在的 skill_marketplace 表补列(幂等,重复列 1060 错误被 continue-on-error 忽略)
ALTER TABLE skill_marketplace ADD COLUMN category VARCHAR(32) NOT NULL DEFAULT 'other';
ALTER TABLE skill_marketplace ADD COLUMN icon VARCHAR(32) NOT NULL DEFAULT '';

-- 技能安装记录:同一用户不可重复安装同名技能(唯一约束)
CREATE TABLE IF NOT EXISTS skill_install (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    user_id     BIGINT      NOT NULL,
    skill_id    BIGINT      NOT NULL COMMENT '对应 skill_marketplace.id',
    skill_name  VARCHAR(64) NOT NULL COMMENT '冗余,便于查重',
    installed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_si_user_skill (user_id, skill_name),
    KEY idx_si_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

