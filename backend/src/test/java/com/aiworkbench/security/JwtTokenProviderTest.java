package com.aiworkbench.security;

import com.aiworkbench.config.JwtProperties;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JwtTokenProvider 单元测试(无 Spring 上下文、无 DB):
 * 校验签发/解析、令牌版本 claim、非法签名拒绝。
 */
class JwtTokenProviderTest {

    private JwtTokenProvider provider() {
        JwtProperties props = new JwtProperties();
        // HS256 需要 >= 32 字节
        props.setSecret("0123456789abcdef0123456789abcdef");
        props.setTtlMinutes(60);
        return new JwtTokenProvider(props);
    }

    @Test
    void generate_parsesSubjectClaimsAndTokenVersion() {
        JwtTokenProvider p = provider();
        String token = p.generate(42L, "alice", "admin", 3);

        assertTrue(p.isValid(token));
        Claims claims = p.parse(token);
        assertEquals("42", claims.getSubject());
        assertEquals("alice", claims.get("username", String.class));
        assertEquals("admin", claims.get("role", String.class));
        assertEquals(3, claims.get("tv", Integer.class));
    }

    @Test
    void nullTokenVersion_defaultsToZero() {
        JwtTokenProvider p = provider();
        Claims claims = p.parse(p.generate(1L, "bob", "user", null));
        assertEquals(0, claims.get("tv", Integer.class));
    }

    @Test
    void tamperedToken_isRejected() {
        JwtTokenProvider p = provider();
        String token = p.generate(1L, "bob", "user", 0);
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("A") ? "BB" : "AA");
        assertFalse(p.isValid(tampered));
    }

    @Test
    void shortSecret_isRejectedAtConstruction() {
        JwtProperties props = new JwtProperties();
        props.setSecret("too-short");
        assertThrows(IllegalStateException.class, () -> new JwtTokenProvider(props));
    }
}
