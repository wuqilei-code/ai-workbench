package com.aiworkbench.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 基于数据库的轻量分布式锁:用于保证多副本部署时,定时调度(任务扫描/队列派发)只有一个实例执行。
 * <p>
 * 实现:一张 {@code app_lock(name PK, owner, expires_at)} 表。
 * 获取 = 尝试 INSERT(不存在时)或条件 UPDATE(已过期/自己持有),带 TTL 过期自动释放。
 * 同一实例持有者再次获取会续期(owner 相同),因此可安全地按调度周期反复 tryLock。
 * 表不可用等异常时降级为「放行」,保证单机场景不受影响。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DbLockService {

    private final JdbcTemplate jdbc;
    /** 实例标识(进程级) */
    private final String owner = UUID.randomUUID().toString();

    /** 尝试获取名为 name 的锁,ttlSeconds 秒后自动过期;返回是否成功持有。 */
    public boolean tryLock(String name, int ttlSeconds) {
        LocalDateTime expires = LocalDateTime.now().plusSeconds(Math.max(1, ttlSeconds));
        try {
            jdbc.update("INSERT INTO app_lock(name, owner, expires_at) VALUES(?,?,?)",
                    name, owner, Timestamp.valueOf(expires));
            return true;
        } catch (DuplicateKeyException dup) {
            // 已存在:仅当已过期或由自己持有时接管/续期
            int n = jdbc.update(
                    "UPDATE app_lock SET owner=?, expires_at=? WHERE name=? AND (owner=? OR expires_at < NOW(3))",
                    owner, Timestamp.valueOf(expires), name, owner);
            return n > 0;
        } catch (Exception e) {
            log.debug("[db-lock] tryLock degraded (table unavailable?): {}", e.toString());
            return true;
        }
    }

    /** 释放自己持有的锁(可选;TTL 到期亦会自动释放)。 */
    public void unlock(String name) {
        try {
            jdbc.update("DELETE FROM app_lock WHERE name=? AND owner=?", name, owner);
        } catch (Exception ignored) {
            // 忽略
        }
    }
}
