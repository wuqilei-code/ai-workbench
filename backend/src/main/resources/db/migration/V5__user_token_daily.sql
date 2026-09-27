-- 每用户每日 token 用量(配额校验与用量展示)。
-- tokens 为当日累计(input+output+reasoning+cache 求和);主键 (user_id, day) 幂等累加。
CREATE TABLE IF NOT EXISTS user_token_daily (
    user_id BIGINT   NOT NULL,
    day     DATE     NOT NULL,
    tokens  BIGINT   NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
