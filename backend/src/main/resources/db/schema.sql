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
    agent_session_id    VARCHAR(128) NOT NULL,
    title               VARCHAR(255) NOT NULL DEFAULT 'New Chat',
    agent               VARCHAR(64)  NOT NULL DEFAULT 'chat',
    provider_id         VARCHAR(64)  NULL,
    model_id            VARCHAR(128) NULL,
    archived            TINYINT      NOT NULL DEFAULT 0,
    pinned              TINYINT      NOT NULL DEFAULT 0,
    skills              JSON         NULL COMMENT '会话勾选启用的自定义技能名数组(deepagents;空=仅内置技能)',
    mcp_servers         JSON         NULL COMMENT '会话级临时启用的 MCP server 名数组(NULL=默认全部已启用;空=本会话不加载)',
    created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_session_agent (agent_session_id),
    KEY idx_session_user (user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 会话置顶:为已存在的 chat_session 表补 pinned 列(幂等,重复列 1060 错误被 continue-on-error 忽略)
ALTER TABLE chat_session ADD COLUMN pinned TINYINT NOT NULL DEFAULT 0;

-- 会话级 MCP 启用:为已存在的 chat_session 表补 mcp_servers 列(幂等,重复列 1060 错误被 continue-on-error 忽略)
ALTER TABLE chat_session ADD COLUMN mcp_servers JSON NULL;

CREATE TABLE IF NOT EXISTS chat_message (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    session_id          BIGINT       NOT NULL,
    user_id             BIGINT       NOT NULL,
    agent_message_id    VARCHAR(128) NULL,
    -- 冗余会话的 provider/model,供按模型统计 token/成本(从 chat_session 复制,写时落列)
    provider_id         VARCHAR(64)  NULL,
    model_id            VARCHAR(128) NULL,
    role                VARCHAR(16)  NOT NULL,
    content             MEDIUMTEXT   NULL,
    parts               JSON         NULL,
    tokens              JSON         NULL COMMENT 'assistant 消息真实 token 用量 {input,output,reasoning,cache:{read,write}}(deepagents 轨道为 NULL)',
    -- 方案 A:从 tokens/parts JSON 抽取的数值列,写时落列,供驾驶舱聚合(避免运行时解析 JSON)
    input_tokens        BIGINT       NULL COMMENT '输入 token(JSON_EXTRACT tokens.input)',
    output_tokens       BIGINT       NULL COMMENT '输出 token(tokens.output)',
    reasoning_tokens    BIGINT       NULL COMMENT '思维链 token(tokens.reasoning)',
    cache_read_tokens   BIGINT       NULL COMMENT '缓存读 token(tokens.cache.read)',
    cache_write_tokens  BIGINT       NULL COMMENT '缓存写 token(tokens.cache.write)',
    tool_call_count     INT          NULL COMMENT '工具调用次数(parts.toolCalls 长度)',
    compaction_summary  TINYINT      NOT NULL DEFAULT 0 COMMENT '1=压缩产生的总结消息(前端不显示,仅占位)',
    status              VARCHAR(16)  NOT NULL DEFAULT 'completed',
    created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_msg_session (session_id, id),
    KEY idx_msg_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 迁移:老库补齐方案 A 数值列(幂等,重复列 1060 错误被 continue-on-error 忽略)
ALTER TABLE chat_message ADD COLUMN input_tokens BIGINT NULL;
ALTER TABLE chat_message ADD COLUMN output_tokens BIGINT NULL;
ALTER TABLE chat_message ADD COLUMN reasoning_tokens BIGINT NULL;
ALTER TABLE chat_message ADD COLUMN cache_read_tokens BIGINT NULL;
ALTER TABLE chat_message ADD COLUMN cache_write_tokens BIGINT NULL;
ALTER TABLE chat_message ADD COLUMN tool_call_count INT NULL;
ALTER TABLE chat_message ADD COLUMN provider_id VARCHAR(64) NULL;
ALTER TABLE chat_message ADD COLUMN model_id VARCHAR(128) NULL;

-- 幂等回填:老数据从 tokens/parts JSON 抽取数值(仅回填尚未抽取的行,避免每次启动全表重写)
UPDATE chat_message SET
  input_tokens       = CAST(JSON_UNQUOTE(JSON_EXTRACT(tokens, '$.input')) AS UNSIGNED),
  output_tokens      = CAST(JSON_UNQUOTE(JSON_EXTRACT(tokens, '$.output')) AS UNSIGNED),
  reasoning_tokens   = CAST(JSON_UNQUOTE(JSON_EXTRACT(tokens, '$.reasoning')) AS UNSIGNED),
  cache_read_tokens  = CAST(JSON_UNQUOTE(JSON_EXTRACT(tokens, '$.cache.read')) AS UNSIGNED),
  cache_write_tokens = CAST(JSON_UNQUOTE(JSON_EXTRACT(tokens, '$.cache.write')) AS UNSIGNED)
WHERE tokens IS NOT NULL AND input_tokens IS NULL;
UPDATE chat_message SET tool_call_count = COALESCE(JSON_LENGTH(JSON_EXTRACT(parts, '$.toolCalls')), 0)
WHERE parts IS NOT NULL AND tool_call_count IS NULL;
-- 回填 provider/model:从所属会话复制(仅回填 model_id 为空的消息)
UPDATE chat_message cm
JOIN chat_session cs ON cs.id = cm.session_id
SET cm.provider_id = cs.provider_id, cm.model_id = cs.model_id
WHERE cm.model_id IS NULL AND cs.model_id IS NOT NULL;

-- 方案 B:用户每日指标汇总表(驾驶舱趋势数据源;按 user_id + 日期 upsert,避免扫 chat_message 大表)
CREATE TABLE IF NOT EXISTS user_daily_metrics (
    id                 BIGINT      NOT NULL AUTO_INCREMENT,
    user_id            BIGINT      NOT NULL,
    stat_date          DATE        NOT NULL,
    messages           BIGINT      NOT NULL DEFAULT 0 COMMENT '可见消息数(user/assistant,不含压缩占位)',
    user_messages      BIGINT      NOT NULL DEFAULT 0,
    assistant_messages BIGINT      NOT NULL DEFAULT 0,
    input_tokens       BIGINT      NOT NULL DEFAULT 0,
    output_tokens      BIGINT      NOT NULL DEFAULT 0,
    reasoning_tokens   BIGINT      NOT NULL DEFAULT 0,
    cache_read_tokens  BIGINT      NOT NULL DEFAULT 0,
    cache_write_tokens BIGINT      NOT NULL DEFAULT 0,
    tool_calls         BIGINT      NOT NULL DEFAULT 0,
    sessions           BIGINT      NOT NULL DEFAULT 0 COMMENT '当日活跃会话数(distinct session)',
    files_changed      BIGINT      NOT NULL DEFAULT 0 COMMENT '当日变更文件数(summary 消息 summaryFiles 长度之和)',
    created_at         DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at         DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_udm_user_date (user_id, stat_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 模型单价表:按模型估算 token 成本(驾驶舱成本卡片);价格可随时在管理端/DB 调整,缺价格的模型成本按 0 计
CREATE TABLE IF NOT EXISTS model_price (
    id           BIGINT         NOT NULL AUTO_INCREMENT,
    provider_id  VARCHAR(64)    NULL,
    model_id     VARCHAR(128)   NOT NULL,
    name         VARCHAR(128)   NOT NULL DEFAULT '',
    input_price  DECIMAL(14,6)  NOT NULL DEFAULT 0 COMMENT '每 1M 输入 token 价格(元)',
    output_price DECIMAL(14,6)  NOT NULL DEFAULT 0 COMMENT '每 1M 输出 token 价格(元)',
    created_at   DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at   DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_mp_model (model_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 常见模型单价种子(近似值,示例用途;按实际接入模型在 DB 里调整)
INSERT INTO model_price (provider_id, model_id, name, input_price, output_price) VALUES
  ('deepseek', 'deepseek-chat', 'DeepSeek Chat', 1.0, 2.0),
  ('deepseek', 'deepseek-reasoner', 'DeepSeek Reasoner', 4.0, 16.0),
  ('openai', 'gpt-4o', 'GPT-4o', 17.5, 70.0),
  ('openai', 'gpt-4o-mini', 'GPT-4o mini', 1.05, 4.2),
  ('anthropic', 'claude-sonnet-4-20250514', 'Claude Sonnet 4', 21.0, 105.0),
  ('anthropic', 'claude-3-5-sonnet-20241022', 'Claude 3.5 Sonnet', 21.0, 105.0)
ON DUPLICATE KEY UPDATE name = VALUES(name), input_price = VALUES(input_price), output_price = VALUES(output_price);

-- 收藏:消息级收藏(用户问题或 AI 回答),原为前端 localStorage,迁至后端便于统计与多端同步
CREATE TABLE IF NOT EXISTS favorite (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    user_id       BIGINT       NOT NULL,
    session_id    BIGINT       NOT NULL,
    message_id    BIGINT       NOT NULL,
    role          VARCHAR(16)  NOT NULL DEFAULT 'assistant' COMMENT 'user | assistant',
    content       TEXT         NULL COMMENT '消息正文(截断)',
    turn_id       VARCHAR(64)  NULL,
    session_title VARCHAR(255) NULL,
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_fav_user_msg (user_id, session_id, message_id),
    KEY idx_fav_user (user_id, id)
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
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    provider_id   VARCHAR(64)  NOT NULL,
    model_id      VARCHAR(128) NOT NULL,
    name          VARCHAR(128) NOT NULL,
    enabled       TINYINT      NOT NULL DEFAULT 1,
    context_limit INT          NULL COMMENT '模型上下文窗口 token 上限(进度环用;缺省用估算兜底)',
    sort_order    INT          NOT NULL DEFAULT 0,
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
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
    icon        VARCHAR(32)  NULL DEFAULT '📋',
    sort_order  INT          NOT NULL DEFAULT 0,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_pt_user (user_id),
    KEY idx_pt_category (category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 老库补齐 icon 列(MySQL 8 不支持 ADD COLUMN IF NOT EXISTS;新库 CREATE TABLE 已含该列,
-- 此处对老库生效,重复列 1060 错误被 spring.sql.init.continue-on-error=true 忽略)
ALTER TABLE prompt_template ADD COLUMN icon VARCHAR(32) NULL DEFAULT '📋' AFTER content;

-- 内置模板种子(幂等:重复主键冲突被 continue-on-error 忽略;icon 列此时已存在)
INSERT IGNORE INTO prompt_template (id, user_id, title, category, content, icon, sort_order) VALUES
  (1, NULL, '写周报', '办公', '请帮我把我的工作内容整理成一份周报,包含:本周完成、下周计划、风险与求助三部分。下面是这周我做的事情:\n\n', '🗓️', 10),
  (2, NULL, '会议纪要', '办公', '请把下面这段讨论整理成会议纪要,包含:会议主题、参会人、讨论要点、结论、待办事项(注明负责人与截止时间)。\n\n', '📝', 20),
  (3, NULL, '写邮件', '办公', '请帮我写一封邮件。我会告诉你收件人、目的和要点,请用专业得体的语气组织成完整邮件,主题清晰、正文分段、结尾礼貌。\n\n', '📧', 30),
  (4, NULL, '总结文档', '办公', '请总结我上传的文档:①核心要点 ②关键数据/结论 ③需要我关注的行动项。保留原始信息,不要编造内容。\n\n', '📚', 40),
  (5, NULL, '数据分析', '办公', '我会给你一份数据文件,请帮我分析并输出:①关键指标 ②主要趋势与结论 ③值得关注的风险点。分析要条理清晰,便于汇报。\n\n', '📊', 50),
  (6, NULL, '调试代码', '开发', '我有一段代码出了问题,请帮我:先定位报错根因,再给出修复方案,最后给出修改后的完整代码。下面是我的代码和报错:\n\n', '🐛', 60),
  (7, NULL, '解读报错', '开发', '下面是我的报错信息,请解读:①发生了什么 ②可能的原因 ③具体的修复步骤。回答请通俗易懂,分步说明。\n\n', '🔍', 70),
  (8, NULL, '代码审查', '开发', '请审查下面这段代码:指出潜在 bug、性能问题、可读性问题,并给出改进建议和修改后的代码。\n\n', '🛠️', 80),
  (9, NULL, 'SQL 优化', '开发', '请优化下面这条 SQL:分析执行计划中的潜在问题(如全表扫描、索引缺失、隐式转换),给出优化后的 SQL 和建索引建议。\n\n', '💾', 90),
  (10, NULL, '需求拆解', '开发', '请把下面这个需求拆解成可执行的开发任务:包括模块划分、接口设计要点、表结构建议、测试要点。\n\n', '🧩', 100),
  (11, NULL, '头脑风暴', '其他', '请围绕我下面提出的问题做头脑风暴:给出多个有创意的方案,并从可行性、成本、效果三个维度做简要点评,最后给一个推荐。\n\n', '💡', 110),
  (12, NULL, '翻译润色', '其他', '请把下面的文字翻译成[目标语言],注意保持语气自然、专业。如有更好的表达请同时给出润色建议。\n\n', '✍️', 120),
  (13, NULL, '源码测试分析', '开发', '我上传了一个项目源码压缩包,请用 test-analysis 技能对源码做静态分析:①生成测试点清单 ②生成测试用例 ③生成测试脚本(通过语法校验) ④输出测试报告。沙箱无法安装依赖,不要运行项目,只做静态分析与语法校验。\n\n', '🧪', 130);

-- 用户模板启用关系:用户把系统内置模板(或自建)添加到自己的首页
CREATE TABLE IF NOT EXISTS user_template (
    user_id     BIGINT       NOT NULL,
    template_id BIGINT       NOT NULL,
    enabled     TINYINT      NOT NULL DEFAULT 1 COMMENT '是否在首页展示',
    sort_order  INT          NOT NULL DEFAULT 0,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id, template_id),
    KEY idx_ut_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 技能广场:用户发布技能到广场供他人安装
CREATE TABLE IF NOT EXISTS skill_marketplace (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    user_id       BIGINT       NOT NULL COMMENT '发布者',
    author_name   VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '发布者用户名(冗余)',
    name          VARCHAR(64)  NOT NULL COMMENT '技能名(小写kebab-case,与 SKILL.md name 一致)',
    description   VARCHAR(1024) NOT NULL DEFAULT '' COMMENT '一句话描述',
    content       MEDIUMTEXT   NOT NULL COMMENT 'SKILL.md 全文',
    files         MEDIUMTEXT   NULL COMMENT '附加文件(JSON:[{path,content}])(SKILL.md 之外的脚本/数据/参考)',
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
ALTER TABLE skill_marketplace ADD COLUMN files MEDIUMTEXT NULL;

-- MCP Server 配置:管理员接入公共 MCP server(user_id=0),每个用户也可配置自己的私有 MCP(user_id>0)。
-- 仅支持 remote(HTTP/SSE URL)类型;local(命令进程)已废弃,不再渲染。
CREATE TABLE IF NOT EXISTS mcp_config (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL DEFAULT 0 COMMENT '0=管理员公共配置,>0=用户私有配置',
    name        VARCHAR(64)  NOT NULL COMMENT 'MCP server 标识(小写 kebab-case,如 mysql/filesystem/context7)',
    type        VARCHAR(16)  NOT NULL DEFAULT 'remote' COMMENT 'remote(HTTP/SSE URL);local 已废弃',
    command     VARCHAR(1024) NULL COMMENT '已废弃(local 用),仅保留兼容',
    url         VARCHAR(512) NULL COMMENT 'remote:server 端点 URL',
    headers     TEXT         NULL COMMENT 'remote:HTTP 请求头(JSON:{key:value}),如 Authorization',
    config      JSON         NULL COMMENT 'manual:手动接口清单(JSON 数组),每个元素一个接口定义',
    environment TEXT         NULL COMMENT '已废弃(local 用),仅保留兼容',
    enabled     TINYINT      NOT NULL DEFAULT 1,
    sort_order  INT          NOT NULL DEFAULT 0,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_mcp_user_name (user_id, name),
    KEY idx_mcp_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 迁移:老库补齐 user_id 列(默认 0=公共)并改唯一键为主键 user_id+name。
-- 幂等性:依赖 spring.sql.init.continue-on-error=true(1060 重复列 / 1091 删除不存在索引 报错被忽略)。
ALTER TABLE mcp_config ADD COLUMN user_id BIGINT NOT NULL DEFAULT 0 COMMENT '0=管理员公共配置,>0=用户私有配置';
ALTER TABLE mcp_config ADD COLUMN config JSON NULL COMMENT 'manual:手动接口清单(JSON 数组)';
ALTER TABLE mcp_config DROP INDEX uk_mcp_name;
ALTER TABLE mcp_config ADD UNIQUE INDEX uk_mcp_user_name (user_id, name);
ALTER TABLE mcp_config ADD INDEX idx_mcp_user (user_id);

-- 定时/一次性任务:用户配置的任务(含 cron 或手动触发),由 JobScheduler/JobQueueDispatcher 排队执行。
-- 每次运行使用独立快照工作区(隔离),全局并发由 opencode.job-max-concurrent 控制。
CREATE TABLE IF NOT EXISTS job_task (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    user_id      BIGINT       NOT NULL,
    name         VARCHAR(128) NOT NULL,
    prompt       TEXT         NOT NULL,
    cron         VARCHAR(64)  NULL COMMENT 'cron 表达式;NULL=手动一次性任务',
    agent        VARCHAR(64)  NOT NULL DEFAULT 'chat',
    auto_approve TINYINT      NOT NULL DEFAULT 1 COMMENT '非交互运行是否自动批准未显式拒绝的权限(--auto)',
    enabled      TINYINT      NOT NULL DEFAULT 1,
    status       VARCHAR(16)  NOT NULL DEFAULT 'pending' COMMENT 'pending/queued/running/success/failed/timeout/cancelled',
    next_run_at  DATETIME(3)  NULL COMMENT '下次触发时间(pending 状态用)',
    queued_at    DATETIME(3)  NULL,
    started_at   DATETIME(3)  NULL,
    finished_at  DATETIME(3)  NULL,
    last_result  TEXT         NULL COMMENT '最近一次运行结果摘要',
    last_error   VARCHAR(1000) NULL,
    container_name VARCHAR(128) NULL COMMENT '运行中的任务容器名',
    run_job_dir  VARCHAR(512) NULL COMMENT '最近一次运行的工作区目录(快照)',
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_job_user (user_id, created_at),
    KEY idx_job_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 定时任务运行历史:每次执行(run)一条记录,关联到 job_task.id。
-- 一个 cron 任务会跑出多次结果,每次运行独立记录结果/日志路径,供「定时任务结果」展示。
CREATE TABLE IF NOT EXISTS job_task_run (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    job_id      BIGINT       NOT NULL,
    user_id     BIGINT       NOT NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT 'running' COMMENT 'running/success/failed/timeout/cancelled',
    started_at  DATETIME(3)  NULL,
    finished_at DATETIME(3)  NULL,
    exit_code   INT          NULL COMMENT '容器退出码(0=成功)',
    changed_files INT        NULL COMMENT '本次回写到真实工作区的文件数',
    result      TEXT         NULL COMMENT '本次运行结果摘要(退出码+回写数+日志尾部)',
    error       VARCHAR(1000) NULL COMMENT '失败原因',
    container_name VARCHAR(128) NULL,
    run_log_path VARCHAR(512) NULL COMMENT '本次运行的日志文件路径(工作区内 jobs/<jobId>/runs/<runId>.log)',
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_run_job (job_id, id),
    KEY idx_run_user (user_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 系统消息中心:定时任务等后台事件产生的用户消息(右上角铃铛展示)。
CREATE TABLE IF NOT EXISTS notification (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    type        VARCHAR(32)  NOT NULL DEFAULT 'task' COMMENT '消息类型:task(定时任务)/system',
    title       VARCHAR(255) NOT NULL COMMENT '消息标题',
    content     TEXT         NULL COMMENT '消息正文(摘要)',
    link        VARCHAR(512) NULL COMMENT '跳转链接(如任务报告文件路径或会话地址)',
    read_flag   TINYINT      NOT NULL DEFAULT 0 COMMENT '0=未读 1=已读',
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_notif_user (user_id, id),
    KEY idx_notif_read (user_id, read_flag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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

-- 技能广场官方种子:user_id=0 表示系统发布(普通用户 id>=1,避免误删);INSERT IGNORE 幂等,同名技能已存在则跳过。
-- 技能可以不止 SKILL.md:files 列携带额外文件(JSON),覆盖"纯指令 / 带脚本 / 带数据+参考"等形态,templates 部署后安装即得完整技能目录。
INSERT INTO skill_marketplace (user_id, author_name, name, description, category, icon, content, files, installs) VALUES
  (0, '系统', 'meeting-minutes', '把会议讨论/录音整理成结构化会议纪要:主题、参会人、要点、结论、行动项(负责人+截止时间)', 'doc', '📝',
   '---\nname: meeting-minutes\ndescription: 当用户要求把会议讨论/录音整理成结构化会议纪要时使用:主题、参会人、要点、结论、行动项(负责人+截止时间)。\n---\n\n# 会议纪要\n\n把原始讨论/录音整理成结构化会议纪要。\n\n## 固定输出结构\n1. 会议主题:一句话概括。\n2. 参会人:从材料提取名单,不要编造。\n3. 讨论要点:按议题分条,每条 1-2 句,去重去口水话。\n4. 结论:只写真正定下来的决定,未定的标「待定」。\n5. 行动项:表格(事项 | 负责人 | 截止时间 | 状态),缺的信息标「待确认」。\n\n## 步骤\n1. 通读材料,提取主题与参会人。\n2. 按议题归并讨论要点。\n3. 区分「结论」和「讨论中」。\n4. 生成行动项表格,缺的信息显式标注而不是猜。\n\n## 规则\n- 绝不编造参会人、截止时间或结论。\n- 信息不全时先问,不要脑补。\n- 输出用 Markdown,标题层级清晰。\n', '[]', 0),
  (0, '系统', 'weekly-report', '把工作内容整理成周报:本周完成、下周计划、风险与求助三部分', 'doc', '🗓️',
   '---\nname: weekly-report\ndescription: 当用户要求把工作内容整理成周报时使用:本周完成、下周计划、风险与求助三部分。\n---\n\n# 周报\n\n把工作记录整理成周报。\n\n## 固定结构(三部分)\n1. 本周完成:按条列成果,尽量量化(数量/百分比/进度),标注状态(已完成/进行中)。\n2. 下周计划:按优先级排序,每条写清目标和预期产出。\n3. 风险与求助:明确风险点、需要的支持/资源。\n\n## 步骤\n1. 从用户给的工作记录里提取本周事项。\n2. 归并同类事项,补上量化数据(缺数据就问,不要编数字)。\n3. 排下周优先级,给出可执行计划。\n4. 汇总风险与求助。\n\n## 规则\n- 简洁、基于事实,不夸大。\n- 缺数字时明确问,不要编造。\n- 用 Markdown,分点清晰。\n', '[]', 0),
  (0, '系统', 'ppt-outline', '把主题/素材扩展成 PPT 大纲:封面、目录、逐页标题与要点、结尾页', 'doc', '🎞️',
   '---\nname: ppt-outline\ndescription: 当用户要求把主题/素材扩展成 PPT 大纲时使用:封面、目录、逐页标题与要点、结尾页。\n---\n\n# PPT 大纲\n\n把主题/素材扩展成清晰的 PPT 大纲。\n\n## 步骤\n1. 确认受众与目标(汇报/提案/培训),这决定详略与语气。\n2. 写封面(标题+副标题)与目录。\n3. 逐页大纲:每页一个观点,3-5 条要点。\n4. 结尾页:总结 / 下一步 / Q&A。\n\n## 大纲格式\n每页一行,如:\n- 页 1:背景与目标 — 为什么做;要解决什么\n- 页 2:现状分析 — 数据;痛点\n\n## 规则\n- 控制在 5-15 页,不要堆字。\n- 每页标题要有信息量、可行动。\n- 本技能附带模板 templates/outline-template.md(安装后技能目录里),可打开逐项填写,或让 AI 直接按模板输出。\n', '[]', 0),
  (0, '系统', 'sql-helper', 'SQL 编写、执行计划分析、索引建议、慢查询剖析,附可运行样例', 'dev', '🗄️',
   '---\nname: sql-helper\ndescription: 当用户要求编写或优化 SQL 时使用:分析执行计划风险(全表扫描、隐式转换、N+1)、改写、给出索引建议。\n---\n\n# SQL 助手\n\n编写 / 审查 / 优化 SQL。\n\n## 步骤\n1. 先弄清表结构与意图(字段、索引、数据量级)。\n2. 审查常见问题:全表扫描、缺索引、隐式类型转换、SELECT *、N+1、无 LIMIT 的大结果集。\n3. 改写 SQL,说明每处改动的原因。\n4. 给出索引建议(DDL),并说明索引命中的条件。\n\n## 参考\nreferences/execution-plan-cheatsheet.md 是执行计划阅读速查;读不懂某行时先把对应规则读出来。\n\n## 规则\n- 只针对用户自己的数据库。\n- 每处优化都解释「为什么」,不只说「怎么做」。\n- 改前确认是否生产库,避免破坏性操作(如不带 LIMIT 的 DELETE)。\n', '[]', 0),
  (0, '系统', 'code-review', '逐段审查代码:bug、安全、性能、可读性,并按严重度给出修复建议', 'dev', '🛡️',
   '---\nname: code-review\ndescription: 当用户要求审查代码时使用:检查 bug、安全、性能、可读性,并按严重度给出可执行的修复建议。\n---\n\n# 代码审查\n\n逐段审查代码,输出分级结论与可执行修复建议。\n\n## 检查清单\n- 正确性:边界条件、空值、并发/竞态、异常处理。\n- 安全:SQL 注入、命令注入、密钥泄露、越权。\n- 性能:大循环、重复 I/O、N+1、无谓的拷贝。\n- 可读性:命名、结构、注释、重复代码。\n\n## 输出格式\n- 每条发现用严重度标签:【严重】【重要】【次要】。\n- 每条给一个具体修复方案 + 理由。\n- 末尾一段总结(总体质量 + 最该先改的 1-3 项)。\n\n## 参考\n评审维度与提问清单见 references/review-checklist.md;给每条建议写清「为什么」。\n\n## 规则\n- 每条发现都要落到具体代码位置,不空泛。\n- 不确定的问题标「需确认」,不要臆断。\n', '[]', 0),
  (0, '系统', 'commit-hint', '按 Git 差异生成 Conventional Commits 提交信息:type/scope/subject + 正文', 'dev', '🔖',
   '---\nname: commit-hint\ndescription: 当用户要求根据 Git 差异生成 Conventional Commits 提交信息时使用:type/scope/subject + 正文。\n---\n\n# 提交信息\n\n根据 Git 差异生成 Conventional Commits 格式的提交信息。\n\n## type 选择\nfix / feat / docs / style / refactor / perf / test / build / chore\n\n## 步骤\n1. 通读 diff,按逻辑改动分组(一个提交只做一件事)。\n2. 选 type 与 scope(可选)。\n3. subject 用祈使句、≤50 字符、首字母小写、结尾不加句号。\n4. 正文写「为什么」和影响,可选脚注(BREAKING CHANGE、关联 issue)。\n\n## 输出示例\nfeat(auth): 增加登录失败锁定策略\n\n防止暴力破解,连续 5 次失败锁定 15 分钟。\n\n## 规则\n- subject 用祈使句、简洁。\n- 正文解释动机,不只复述 diff。\n- 不编造 diff 里没有的变更。\n', '[]', 0),
  (0, '系统', 'zh-polish', '改写中文,优化用词、语法与结构,保留原义', 'writing', '🖋️',
   '---\nname: zh-polish\ndescription: 当用户要求润色中文文本时使用:优化用词、语法、语气与结构,保留原义。\n---\n\n# 中文润色\n\n改写中文,优化表达,保留原义。\n\n## 步骤\n1. 修语法与标点(错别字、病句、标点误用)。\n2. 优化用词:去冗余、口语书面化、术语统一。\n3. 调整结构:拆分长句、理顺衔接、删重复。\n\n## 输出\n- 默认给「原文 | 改写 | 备注」三列对比(改动处标注原因)。\n- 用户要求整段重排时,直接给润色后的完整文本 + 简短改动说明。\n\n## 规则\n- 保留作者原意与风格倾向(正式/口语)。\n- 不擅自增删信息。\n- 改动要点在备注里说明,方便用户确认。\n', '[]', 0),
  (0, '系统', 'translate', '中英互译,保留原意与语气,输出自然译文', 'writing', '🌐',
   '---\nname: translate\ndescription: 当用户要求在不同语言间翻译时使用,保留原意与语气,输出自然译文。\n---\n\n# 翻译\n\n在不同语言间翻译,输出自然译文。\n\n## 步骤\n1. 明确目标语言与风格(正式/口语、技术/通用)。\n2. 保留原意、语气与术语一致性。\n3. 专业术语按领域习惯翻译,专有名词保留原文或括注。\n4. 语义有歧义时先问,不要猜。\n\n## 输出\n- 默认直接给译文;差异大时附简短说明。\n- 多段/长文分段输出,保持段落对应。\n\n## 规则\n- 保留原意与语气,不增删信息。\n- 术语全文统一。\n- 拿不准的歧义先确认。\n', '[]', 0),
  (0, '系统', 'data-cleaner', '清洗 CSV/Excel 数据:去重、去空、类型修正、口径统一,输出整洁数据集与清洗报告', 'data', '🧹',
   '---\nname: data-cleaner\ndescription: 当用户需要清洗表格数据(CSV/XLSX)时使用:去重、去空、类型修正、口径统一,输出整洁数据集与清洗报告。\n---\n\n# 数据清洗\n\n清洗表格数据,输出干净数据集 + 清洗报告。\n\n## 自带脚本\n技能附带了 scripts/clean.py(pandas 清洗模板)与 data/sample.csv 示例数据:\n\n    python scripts/clean.py data/sample.csv\n\n把脚本复制到项目根,按真实列名调整后运行。\n\n## 步骤\n1. 读数据,打印 shape/columns/dtypes/head。\n2. 去重、去空、类型修正(pd.to_numeric/to_datetime)、列名/口径统一(去空格、大小写)。\n3. 输出 cleaned_<src>.csv + 清洗报告(删了几行、填了哪些缺失、改了哪些类型)。\n4. 清理一次性脚本(可复用脚本保留)。\n\n## 规则\n- 不改动原始文件,始终输出新文件。\n- 缺失值处理要说清策略(删除/填充)。\n- 用 pandas/matplotlib,环境已预装,pip 已移除不可重装。\n', '[]', 0)
ON DUPLICATE KEY UPDATE
  description = VALUES(description),
  content = VALUES(content),
  category = VALUES(category),
  icon = VALUES(icon);

-- 幂等回填:已存在(INSERT IGNORE 跳过)的官方技能补充附加文件,让老库也拿到多文件形态
UPDATE skill_marketplace SET files='[{"path":"templates/outline-template.md","content":"# 大纲模板填充说明\\n\\n## 封面\\n- 标题\\n- 副标题\\n- 作者/日期\\n\\n## 目录\\n1. 背景与目标\\n2. 核心内容\\n3. 总结与推进\\n"}]' WHERE user_id=0 AND name='ppt-outline';
UPDATE skill_marketplace SET files='[{"path":"references/execution-plan-cheatsheet.md","content":"# 执行计划速查\\n- type=ALL: 全表扫描, 优先补索引。\\n- Extra contains filesort / temporary: 需要优化。\\n- 隐式转换: 会打断索引。\\n"}]' WHERE user_id=0 AND name='sql-helper';
UPDATE skill_marketplace SET files='[{"path":"references/review-checklist.md","content":"# 评审清单\\n- 边界/空/并发\\n- 注入与密钥泄露\\n- 大循环、N+1\\n- 命名与可读性\\n"}]' WHERE user_id=0 AND name='code-review';
-- data-cleaner attached files backfill
UPDATE skill_marketplace SET files='[{"content":"#!/usr/bin/env python3\\n# -*- coding: utf-8 -*-\\n\\"\\"\\"CSV cleaning scaffold: dedupe, drop empties, trim whitespace, unify types.\\"\\"\\"\\nimport sys\\nimport pandas as pd\\n\\n\\ndef clean(df):\\n    df = df.drop_duplicates()\\n    df = df.dropna(how=''all'')\\n    for col in df.select_dtypes(include=''object'').columns:\\n        df[col] = df[col].astype(str).str.strip()\\n    return df\\n\\n\\ndef main():\\n    src = sys.argv[1]\\n    df = pd.read_csv(src)\\n    df = clean(df)\\n    df.to_csv(''cleaned_'' + src.split(''/'')[-1], index=False)\\n\\n\\nif __name__ == ''__main__'':\\n    main()","path":"scripts/clean.py"},{"content":"name,dept,email,age\\n张三,研发,zhang@acme.io,30\\n李四,研发,lisi@acme.io,28\\n张三,研发,zhang@acme.io,30\\n王五,市场,wang@acme.io,32\\n孔,市场,,34","path":"data/sample.csv"}]' WHERE user_id=0 AND name='data-cleaner';


-- 用户技能表:存储用户自建与从市场安装的技能(私有/公开)。
CREATE TABLE IF NOT EXISTS user_skill (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL COMMENT '归属用户',
    name        VARCHAR(128) NOT NULL COMMENT '技能名(与 skills/<name>/SKILL.md 目录一致)',
    description VARCHAR(512) NOT NULL DEFAULT '',
    content     MEDIUMTEXT   NULL COMMENT 'SKILL.md 正文(含 frontmatter)',
    files       JSON         NULL COMMENT '附加文件 [{path,content}](脚本/数据/参考等)',
    visibility  TINYINT      NOT NULL DEFAULT 0 COMMENT '0=私有 1=公开(公开进技能市场)',
    source      VARCHAR(16)  NOT NULL DEFAULT 'self' COMMENT 'self=自建 installed=从市场安装',
    source_skill_id BIGINT   NULL COMMENT 'installed 来源技能(id)',
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_skill_name (user_id, name),
    KEY idx_user_skill_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Phase: 去 opencode 化 —— 列改名(opencode_* → agent_*)。
-- 幂等性:新库 CREATE TABLE 已建新列,下面语句报"未知列/重复索引/未知索引"错误由 continue-on-error 忽略;
-- 老库首次启动完成改名,之后报错忽略。
ALTER TABLE chat_session RENAME COLUMN opencode_session_id TO agent_session_id;
ALTER TABLE chat_message RENAME COLUMN opencode_message_id TO agent_message_id;
ALTER TABLE chat_session DROP INDEX uk_session_opencode;
ALTER TABLE chat_session ADD UNIQUE INDEX uk_session_agent (agent_session_id);
-- 会话技能选择列(老库补齐,幂等)
ALTER TABLE chat_session ADD COLUMN skills JSON NULL;
-- 用户技能附加文件列(老库补齐,幂等)
ALTER TABLE user_skill ADD COLUMN files JSON NULL;
