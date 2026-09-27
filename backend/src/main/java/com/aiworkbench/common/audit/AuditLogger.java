package com.aiworkbench.common.audit;

import com.aiworkbench.entity.AuditLog;
import com.aiworkbench.mapper.AuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 审计日志异步写入器。@Async 避免阻塞主请求线程(@EnableAsync 已在主类开启)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditLogger {

    private final AuditLogMapper auditLogMapper;

    @Async
    public void log(Long userId, String username, String action, String target,
                    String ip, String method, String path, String status,
                    long costMs, String errorMsg) {
        try {
            AuditLog rec = new AuditLog();
            rec.setUserId(userId);
            rec.setUsername(username);
            rec.setAction(action);
            // target/errorMsg 可能含参数文本或异常消息:脱敏后入库(审计表可在管理端查看)
            rec.setTarget(SecretRedactor.maskAndTruncate(target, 500));
            rec.setIp(ip);
            rec.setMethod(method);
            rec.setPath(path);
            rec.setStatus(status);
            rec.setCostMs(costMs);
            rec.setErrorMsg(SecretRedactor.maskAndTruncate(errorMsg, 1000));
            auditLogMapper.insert(rec);
        } catch (Exception e) {
            // 审计失败不应影响业务,仅告警
            log.warn("write audit log failed: {}", e.getMessage());
        }
    }
}
