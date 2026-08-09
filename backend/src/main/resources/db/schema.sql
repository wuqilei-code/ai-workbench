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
INSERT IGNORE INTO skill_marketplace (user_id, author_name, name, description, category, icon, content, files, installs) VALUES
  (0, '系统', 'meeting-minutes', '把会议讨论/录音整理成结构化会议纪要:主题、参会人、要点、结论、行动项(负责人+截止时间)', 'doc', '📝',
   '---\nname: meeting-minutes\ndescription: Use when the user asks to create meeting minutes from a discussion or transcript: topic, attendees, key points, decisions and action items.\n---\n\n# Meeting Minutes\n\nTurn raw notes into structured minutes.\n\n## Steps\n1. Identify the topic and attendees.\n2. Extract the key points per discussion item.\n3. Record conclusions explicitly.\n4. List action items as a table: owner, due date, status.\n\n## Rules\n- Never invent attendees, deadlines or decisions.\n- Ask for missing essentials before finishing.\n', '[]', 0),
  (0, '系统', 'weekly-report', '把工作内容整理成周报:本周完成、下周计划、风险与求助三部分', 'doc', '🗓️',
   '---\nname: weekly-report\ndescription: Use when the user asks to write a weekly report from a work log: what was done, what is planned, risks and help needed.\n---\n\n# Weekly Report\n\nTurn work notes into a weekly report.\n\n## 结构\n1. 本周完成:列出成果,尽量量化。\n2. 下周计划:按优先级排列。\n3. 风险与求助:明说风险点与需要的帮助。\n\n## Rules\n- Keep it concise and reality-based.\n- Ask for missing numbers instead of inventing.\n', '[]', 0),
  (0, '系统', 'ppt-outline', '把主题/素材扩展成 PPT 大纲:封面、目录、逐页标题与要点、结尾页', 'doc', '🎞️',
   '---\nname: ppt-outline\ndescription: Use when the user asks to build a slide deck outline from a topic: cover, agenda, per-slide bullets, closing slide.\n---\n\n# PPT Outline\n\nTurn a topic/subject into a clear slide outline.\n\n## Steps\n1. Confirm audience & goal (report/pitch/training).\n2. Draft cover + agenda.\n3. Write each slide: one idea, 3-5 bullets.\n4. Close with summary / Q&A.\n\n## 文件\n本技能附带了大纲模板 `templates/outline-template.md`(见安装后的技能目录),打开并逐项填写,或让 AI 直接按模板输出。\n\n## Rules\n- Keep 5-15 slides; no wall of text.\n- Make each slide title actionable.\n',
   '[]', 0),
  (0, '系统', 'sql-helper', 'SQL 编写、执行计划分析、索引建议、慢查询剖析,附可运行样例', 'dev', '🗄️',
   '---\nname: sql-helper\ndescription: Use when the user asks to write or optimize SQL: analyze execution plan risks (full scan, implicit conversion), rewrite, and suggest indexes.\n---\n\n# SQL Helper\n\nWrite / review / optimize SQL.\n\n## Steps\n1. Clarify schema and intent.\n2. Review: full scan, missing index, implicit type conversion, N+1.\n3. Rewrite query, explain the change.\n4. Suggest indexes (DDL).\n\n## 参考\n`references/execution-plan-cheatsheet.md` 里是执行计划阅读速查;读不懂某行时先把对应规则读出来。\n\n## Rules\n- Only against the user''s database.\n- Explain why, not just what.\n', '[]', 0),
  (0, '系统', 'code-review', '逐段审查代码:bug、安全、性能、可读性,并按严重度给出修复建议', 'dev', '🛡️',
   '---\nname: code-review\ndescription: Use when the user asks to review code for bugs, security, performance and readability, and suggest fixes.\n---\n\n# Code Review\n\n审查提交的代码,输出分级结论与可执行修复建议。\n\n## Checklist\n- Correctness: edges, nulls, races.\n- Security: injection, secrets.\n- Performance: loops, I/O.\n- Readability: naming, structure.\n\n## Output\n- Severity tags (Critical/Major/Minor).\n- One fix per finding.\n- A short summary.\n\n## 参考\n评审维度与提问清单见 `references/review-checklist.md`;给每条建议写清"为什么"。\n\n## Rules\n- Ground every finding; never guess.\n', '[]', 0),
  (0, '系统', 'commit-hint', '按 Git 差异生成 Conventional Commits 提交信息:type/scope/subject + 正文', 'dev', '🔖',
   '---\nname: commit-hint\ndescription: Use when the user asks to write a commit message from a diff in Conventional Commits format.\n---\n\n# Commit Message\n\n## type\nfix / feat / docs / style / refactor / perf / test / build / chore\n\n## 步骤\n1. 通读 diff,按逻辑改动分组。\n2. 选 type 与 scope。\n3. subject 用祈使句、≤50 字符;正文写"为什么"。\n\n## Rules\n- subject imperative & concise.\n- body explains why.\n- 不要编造变更未涉及的描述。\n', '[]', 0),
  (0, '系统', 'zh-polish', '改写中文,优化用词、语法与结构,保留原义', 'writing', '🖋️',
   '---\nname: zh-polish\ndescription: Use the user asks to polish Chinese text: improve wording, grammar, tone and structure while keeping meaning.\n---\n\n# Chinese Polish\n\n## Steps\n1. 修语法与标点。\n2. 自然用词:去掉冗余、术语统一。\n3. 段落结构:拆分长句、理顺衔接。\n\n## 输出\n优先输出三列对比:原文 | 改写 | 备注;或按用户要求整段重排。\n\n## Rules\n- Keep the author''s intent.\n- Offer diff-style summary.\n', '[]', 0),
  (0, '系统', 'translate', '中英互译,保留原意与语气,输出自然译文', 'writing', '🌐',
   '---\nname: translate\ndescription: Use when the user asks to translate between languages while keeping meaning.\n---\n\n# Translate\n\n## Steps\n- Preserve meaning & tone.\n- Natural target expression.\n- Ask when ambiguous.\n', '[]', 0),
  (0, '系统', 'data-cleaner', '清洗 CSV/Excel 数据:去重、去空、类型修正、口径统一,输出整洁数据集与清洗报告', 'data', '🧹',
   '---\nname: data-cleaner\ndescription: Use when the user needs to clean tabular data (CSV/XLSX): dedupe, drop empties, normalize types, unify columns; output cleaned dataset and a cleaning report.\n---\n\n# Data Cleaner\n\n清洗表格数据并输出拆分干净的数据集 + 清洗报告。\n\n## 自带脚本\n技能附带了 `scripts/clean.py`(pandas 清洗模板)与 `data/sample.csv` 示例数据,用于观察运行效果: 运行\n\n    python scripts/clean.py data/sample.csv\n\n复制脚本到项目根后按真实列名调整即可(清洗逻辑请保留输出报告)。\n\n## Steps\n1. 读数据,打印 shape/columns/head。\n2. 去重、去空、类型修正、列名/口径统一。\n3. 输出 `cleaned_<src>.csv` + 一段清洗报告(删了几行、填了哪些缺失、改了哪些类型)。\n4. 清理一次性脚本(保留可复用脚本)。\n\n## Rules\n- 不改动原始文件,始终输出新文件。\n- 用到 pandas/matplotlib;环境预装,不要 drop 重装(pip 已移除)。\n', '[]', 0);
-- 幂等回填:已存在(INSERT IGNORE 跳过)的官方技能补充附加文件,让老库也拿到多文件形态
UPDATE skill_marketplace SET files='[{"path":"templates/outline-template.md","content":"# 大纲模板填充说明\\n\\n## 封面\\n- 标题\\n- 副标题\\n- 作者/日期\\n\\n## 目录\\n1. 背景与目标\\n2. 核心内容\\n3. 总结与推进\\n"}]' WHERE user_id=0 AND name='ppt-outline' AND (files IS NULL OR files='[]');
UPDATE skill_marketplace SET files='[{"path":"references/execution-plan-cheatsheet.md","content":"# 执行计划速查\\n- type=ALL: 全表扫描, 优先补索引。\\n- Extra contains filesort / temporary: 需要优化。\\n- 隐式转换: 会打断索引。\\n"}]' WHERE user_id=0 AND name='sql-helper' AND (files IS NULL OR files='[]');
UPDATE skill_marketplace SET files='[{"path":"references/review-checklist.md","content":"# 评审清单\\n- 边界/空/并发\\n- 注入与密钥泄露\\n- 大循环、N+1\\n- 命名与可读性\\n"}]' WHERE user_id=0 AND name='code-review' AND (files IS NULL OR files='[]');
-- data-cleaner attached files backfill
UPDATE skill_marketplace SET files='[{"content":"#!/usr/bin/env python3\\n# -*- coding: utf-8 -*-\\n\\"\\"\\"CSV cleaning scaffold: dedupe, drop empties, trim whitespace, unify types.\\"\\"\\"\\nimport sys\\nimport pandas as pd\\n\\n\\ndef clean(df):\\n    df = df.drop_duplicates()\\n    df = df.dropna(how=''all'')\\n    for col in df.select_dtypes(include=''object'').columns:\\n        df[col] = df[col].astype(str).str.strip()\\n    return df\\n\\n\\ndef main():\\n    src = sys.argv[1]\\n    df = pd.read_csv(src)\\n    df = clean(df)\\n    df.to_csv(''cleaned_'' + src.split(''/'')[-1], index=False)\\n\\n\\nif __name__ == ''__main__'':\\n    main()","path":"scripts/clean.py"},{"content":"name,dept,email,age\\n张三,研发,zhang@acme.io,30\\n李四,研发,lisi@acme.io,28\\n张三,研发,zhang@acme.io,30\\n王五,市场,wang@acme.io,32\\n孔,市场,,34","path":"data/sample.csv"}]' WHERE user_id=0 AND name='data-cleaner' AND (files IS NULL OR files='[]');

