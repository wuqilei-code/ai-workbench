-- 会话 SSE 事件出站表:跨副本广播用。
-- 单副本时事件只在进程内分发;多副本时由各实例把事件写入本表(带 origin=实例id),
-- 其它实例轮询 seq 增量并把非本实例产生的事件投递给本地订阅者。行保留数分钟后清理。
CREATE TABLE IF NOT EXISTS session_event (
    seq        BIGINT      NOT NULL AUTO_INCREMENT,
    session_id VARCHAR(64) NOT NULL,
    origin     VARCHAR(64) NOT NULL COMMENT '产生该事件的实例 id(用于避免本实例重复投递)',
    type       VARCHAR(48) NOT NULL COMMENT '事件名:delta/tool/tool_result/...',
    data       MEDIUMTEXT  NULL COMMENT '事件载荷(JSON 文本)',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (seq),
    KEY idx_session_seq (session_id, seq),
    KEY idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
