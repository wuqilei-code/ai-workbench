package com.aiworkbench.service.quota;

import com.aiworkbench.common.BusinessException;
import com.aiworkbench.common.ResultCode;
import com.aiworkbench.mapper.UserTokenDailyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * 每用户每日 token 配额。
 * <p>
 * 配额值来自 {@code app.quota.daily-tokens}(0 = 不限,默认)。用量按日累计于
 * {@code user_token_daily};发送消息前校验,超限抛 429,前端据错误码提示。
 * 累加/查询失败一律静默(fail-open),绝不因配额统计故障阻断正常对话。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenQuotaService {

    private final UserTokenDailyMapper mapper;

    /** 每日 token 上限;<=0 表示不限制。 */
    @Value("${app.quota.daily-tokens:0}")
    private long dailyTokens;

    /** 是否启用配额(配置了正整数即启用)。 */
    public boolean enabled() {
        return dailyTokens > 0;
    }

    public long limit() {
        return dailyTokens;
    }

    /** 当日已用 token(查询失败返回 0)。 */
    public long todayTokens(Long userId) {
        if (userId == null) {
            return 0;
        }
        try {
            Long v = mapper.selectTokens(userId, LocalDate.now());
            return v == null ? 0 : v;
        } catch (Exception e) {
            log.debug("read daily token usage failed (user={}): {}", userId, e.toString());
            return 0;
        }
    }

    /** 累加当日 token(失败静默)。 */
    public void addUsage(Long userId, long delta) {
        if (userId == null || delta <= 0) {
            return;
        }
        try {
            mapper.addTokens(userId, LocalDate.now(), delta);
        } catch (Exception e) {
            log.debug("add daily token usage failed (user={}, delta={}): {}", userId, delta, e.toString());
        }
    }

    /** 校验是否在配额内;超限抛 429。 */
    public void assertWithinQuota(Long userId) {
        if (!enabled() || userId == null) {
            return;
        }
        long used = todayTokens(userId);
        if (used >= dailyTokens) {
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS.getCode(),
                    "已达今日 token 配额(" + dailyTokens + "),请明日再试或联系管理员调整");
        }
    }
}
