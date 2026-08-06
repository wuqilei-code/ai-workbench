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
    created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_session_opencode (opencode_session_id),
    KEY idx_session_user (user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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
