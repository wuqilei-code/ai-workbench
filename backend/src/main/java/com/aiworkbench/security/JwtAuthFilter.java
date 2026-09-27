package com.aiworkbench.security;

import com.aiworkbench.entity.User;
import com.aiworkbench.mapper.UserMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtTokenProvider tokenProvider;
    private final UserMapper userMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // 依次尝试 header(Bearer) → cookie,任一有效即放行。
        // 不支持 query token:避免 JWT 暴露进 URL(预览已改用同源 HttpOnly cookie 鉴权)。
        if (tryAuthenticate(headerToken(request), request)
                || tryAuthenticate(cookieToken(request), request)) {
            chain.doFilter(request, response);
            return;
        }
        // 无任何有效凭据:保持匿名,由后续鉴权规则决定 401
        SecurityContextHolder.clearContext();
        chain.doFilter(request, response);
    }

    private String headerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }

    private String cookieToken(HttpServletRequest request) {
        jakarta.servlet.http.Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (jakarta.servlet.http.Cookie c : cookies) {
                if ("aiwb_token".equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) {
                    return c.getValue();
                }
            }
        }
        return null;
    }

    /** 尝试用 token 建立认证;成功返回 true,失败返回 false(不抛错、不清空已有认证)。 */
    private boolean tryAuthenticate(String token, HttpServletRequest request) {
        if (token == null || token.isBlank()) {
            return false;
        }
        try {
            Claims claims = tokenProvider.parse(token);
            Long userId = Long.valueOf(claims.getSubject());
            // 账号被禁用时,即使 JWT 未过期也立即拒绝(管理员改状态即时生效,不依赖 token 到期)。
            User dbUser = userMapper.selectById(userId);
            if (dbUser == null || Integer.valueOf(0).equals(dbUser.getStatus())) {
                SecurityContextHolder.clearContext();
                return false;
            }
            // 令牌版本校验:改密后库中 token_version +1,旧 token 立即失效
            Integer tokenTv = claims.get("tv", Integer.class);
            int expectedTv = dbUser.getTokenVersion() == null ? 0 : dbUser.getTokenVersion();
            if ((tokenTv == null ? 0 : tokenTv) != expectedTv) {
                SecurityContextHolder.clearContext();
                return false;
            }
            String username = claims.get("username", String.class);
            // 角色以库中最新值为准(改角色即时生效,无需等待 token 过期)
            String role = dbUser.getRole() != null ? dbUser.getRole() : claims.get("role", String.class);
            List<SimpleGrantedAuthority> authorities =
                    List.of(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()));

            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(userId, username, authorities);
            auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(auth);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
