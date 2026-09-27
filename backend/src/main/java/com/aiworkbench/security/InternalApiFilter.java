package com.aiworkbench.security;

import com.aiworkbench.common.R;
import com.aiworkbench.common.ResultCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 内部接口统一鉴权:{@code /api/internal/**} 必须携带与 {@code app.internal-token} 一致的
 * {@code X-Internal-Token}(常量时间比较),否则 403。未配置令牌时一律拒绝(fail-closed)。
 * <p>
 * 可选按来源网段收紧:{@code app.internal-trusted-cidrs}(逗号分隔的 IPv4 或 CIDR,如
 * {@code 172.18.0.0/16,127.0.0.1})。默认空 = 不限制来源,仅校验令牌。
 * <p>
 * 由 {@link com.aiworkbench.config.SecurityConfig} 注册到安全过滤链,覆盖所有内部端点,
 * 避免逐个 controller 自行校验时遗漏。
 */
@Slf4j
@Component
public class InternalApiFilter extends OncePerRequestFilter {

    private static final String PREFIX = "/api/internal/";

    private final ObjectMapper objectMapper;
    private final String internalToken;
    private final List<String> trustedExact = new ArrayList<>();
    private final List<long[]> trustedRanges = new ArrayList<>(); // [network, mask]

    public InternalApiFilter(ObjectMapper objectMapper,
                             @Value("${app.internal-token:}") String internalToken,
                             @Value("${app.internal-trusted-cidrs:}") String trustedCidrs) {
        this.objectMapper = objectMapper;
        this.internalToken = internalToken;
        parseCidrs(trustedCidrs);
    }

    private void parseCidrs(String trustedCidrs) {
        if (trustedCidrs == null || trustedCidrs.isBlank()) {
            return;
        }
        for (String raw : trustedCidrs.split(",")) {
            String v = raw.trim();
            if (v.isEmpty()) {
                continue;
            }
            int slash = v.indexOf('/');
            if (slash < 0) {
                trustedExact.add(v);
                continue;
            }
            String ipPart = v.substring(0, slash);
            int bits;
            try {
                bits = Integer.parseInt(v.substring(slash + 1).trim());
            } catch (NumberFormatException e) {
                log.warn("invalid internal-trusted-cidrs entry (bad prefix): {}", v);
                continue;
            }
            long ip = ipv4ToLong(ipPart);
            if (ip < 0 || bits < 0 || bits > 32) {
                log.warn("invalid internal-trusted-cidrs entry: {}", v);
                continue;
            }
            long mask = bits == 0 ? 0L : (0xFFFFFFFFL << (32 - bits)) & 0xFFFFFFFFL;
            trustedRanges.add(new long[]{ip & mask, mask});
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String remote = request.getRemoteAddr();

        if (internalToken == null || internalToken.isBlank()) {
            reject(response, "internal token not configured", request, remote);
            return;
        }
        String token = request.getHeader("X-Internal-Token");
        if (token == null || token.isBlank()
                || !MessageDigest.isEqual(internalToken.getBytes(StandardCharsets.UTF_8),
                        token.getBytes(StandardCharsets.UTF_8))) {
            reject(response, "bad internal token", request, remote);
            return;
        }
        if (!trusted(remote)) {
            reject(response, "source not in trusted cidrs", request, remote);
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean trusted(String remote) {
        if (trustedExact.isEmpty() && trustedRanges.isEmpty()) {
            return true;
        }
        if (trustedExact.contains(remote)) {
            return true;
        }
        long ip = ipv4ToLong(remote);
        if (ip < 0) {
            return false;
        }
        for (long[] r : trustedRanges) {
            if ((ip & r[1]) == r[0]) {
                return true;
            }
        }
        return false;
    }

    private static long ipv4ToLong(String ip) {
        if (ip == null) {
            return -1;
        }
        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return -1;
        }
        long v = 0;
        for (String p : parts) {
            int b;
            try {
                b = Integer.parseInt(p);
            } catch (NumberFormatException e) {
                return -1;
            }
            if (b < 0 || b > 255) {
                return -1;
            }
            v = (v << 8) | b;
        }
        return v;
    }

    private void reject(HttpServletResponse response, String reason,
                        HttpServletRequest request, String remote) throws IOException {
        log.warn("403 internal api rejected: {} {} (remote={}, reason={})",
                request.getMethod(), request.getRequestURI(), remote, reason);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(R.fail(ResultCode.FORBIDDEN)));
    }
}
