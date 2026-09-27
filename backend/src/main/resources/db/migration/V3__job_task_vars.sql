-- 定时任务变量:JSON object(键→值),运行时替换 prompt 中的占位符。
-- 幂等:仅当 job_task 存在且 vars 列缺失时才 ADD COLUMN(兼容全新库:此时表尚未由 schema.sql 创建,迁移安全跳过)。
SET @has_table := (SELECT COUNT(*) FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'job_task');
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'job_task' AND COLUMN_NAME = 'vars');
SET @ddl := IF(@has_table = 1 AND @has_col = 0,
               'ALTER TABLE job_task ADD COLUMN vars TEXT NULL COMMENT ''job task variables (JSON)''',
               'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
