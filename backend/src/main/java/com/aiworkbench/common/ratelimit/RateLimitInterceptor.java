package com.aiworkbench.common.ratelimit;

import com.aiworkbench.common.R;
import com.aiworkbench.common.ResultCode;
import com.aiworkbench.config.RateLimitProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 令牌桶限流拦截器:按 userId(认证) 或 IP(匿名) 维度限流。
 * SSE 事件流(Accept: text/event-stream)放行,避免长连接被限流阻塞。
 * <p>
 * 令牌桶为自实现(零外部依赖):按时间匀速补充令牌,synchronized 保证线程安全。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimitProperties props;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) throws Exception {
        // SSE 长连接放行
        String accept = req.getHeader("Accept");
        if (accept != null && accept.contains("text/event-stream")) {
            return true;
        }
        String key = resolveKey(req);
        TokenBucket bucket = buckets.computeIfAbsent(key,
                k -> new TokenBucket(props.getCapacity(), props.getRefillTokens(), props.getRefillPeriodSeconds()));
        if (bucket.tryConsume()) {
            return true;
        }
        log.debug("rate limit hit: key={} {} {}", key, req.getMethod(), req.getRequestURI());
        resp.setStatus(429);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(objectMapper.writeValueAsString(R.fail(ResultCode.TOO_MANY_REQUESTS)));
        return false;
    }

    private String resolveKey(HttpServletRequest req) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Long id) {
            return "u:" + id;
        }
        return "ip:" + clientIp(req);
    }

    private String clientIp(HttpServletRequest req) {
        // 优先信任 nginx 注入的 X-Real-IP(真实客户端 IP,不可被请求头伪造)。
        // X-Forwarded-For 可由客户端随意构造,仅作无 nginx 环境(直连后端)的 fallback。
        String real = req.getHeader("X-Real-IP");
        if (real != null && !real.isEmpty()) return real;
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) {
            return xff.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }

    /**
     * 简单令牌桶:按经过时间匀速补充令牌(令牌数可为小数,消费时按 1 个计),
     * 桶满不再累加。synchronized 保证多线程下 tokens/lastRefillNanos 一致。
     */
    private static final class TokenBucket {
        private final int capacity;
        private final double tokensPerNano;
        private double tokens;
        private long lastRefillNanos;

        TokenBucket(int capacity, int refillTokens, int refillPeriodSeconds) {
            this.capacity = capacity;
            this.tokensPerNano = refillTokens / (refillPeriodSeconds * 1_000_000_000.0);
            this.tokens = capacity;
            this.lastRefillNanos = System.nanoTime();
        }

        synchronized boolean tryConsume() {
            long now = System.nanoTime();
            long elapsed = now - lastRefillNanos;
            if (elapsed > 0) {
                tokens = Math.min(capacity, tokens + elapsed * tokensPerNano);
                lastRefillNanos = now;
            }
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }
}
