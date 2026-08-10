package com.aiworkbench.security;

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

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // 依次尝试 header(Bearer) → query token → cookie,任一有效即放行;
        // 避免某个无效来源(如页面自带的无关 token 参数)覆盖掉本已有效的 cookie 鉴权。
        if (tryAuthenticate(headerToken(request), request)
                || tryAuthenticate(request.getParameter("token"), request)
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
            String username = claims.get("username", String.class);
            String role = claims.get("role", String.class);
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
