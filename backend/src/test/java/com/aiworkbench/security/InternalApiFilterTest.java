package com.aiworkbench.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * InternalApiFilter 单元测试(无 Spring 上下文):
 * 内部路径必须携带正确 X-Internal-Token;令牌缺失 fail-closed;可选 CIDR 来源收紧。
 */
class InternalApiFilterTest {

    private static final String URN = "/api/internal/users/1/skills/upsert";

    private record Result(int status, boolean passed) {}

    private Result run(InternalApiFilter filter, String uri, String token, String remote) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", uri);
        if (token != null) req.addHeader("X-Internal-Token", token);
        req.setRemoteAddr(remote);
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicBoolean passed = new AtomicBoolean(false);
        FilterChain chain = (rq, rs) -> passed.set(true);
        filter.doFilter(req, res, chain);
        return new Result(res.getStatus(), passed.get());
    }

    @Test
    void nonInternalPath_passesWithoutToken() throws Exception {
        InternalApiFilter f = new InternalApiFilter(new ObjectMapper(), "secret", "");
        Result r = run(f, "/api/jobs", null, "10.0.0.9");
        assertTrue(r.passed());
        assertEquals(200, r.status());
    }

    @Test
    void internalPath_validToken_passes() throws Exception {
        InternalApiFilter f = new InternalApiFilter(new ObjectMapper(), "secret", "");
        Result r = run(f, URN, "secret", "127.0.0.1");
        assertTrue(r.passed());
    }

    @Test
    void internalPath_wrongOrMissingToken_403() throws Exception {
        InternalApiFilter f = new InternalApiFilter(new ObjectMapper(), "secret", "");
        assertFalse(run(f, URN, "nope", "127.0.0.1").passed());
        assertEquals(403, run(f, URN, "nope", "127.0.0.1").status());
        assertFalse(run(f, URN, null, "127.0.0.1").passed());
    }

    @Test
    void internalPath_tokenNotConfigured_failClosed() throws Exception {
        InternalApiFilter f = new InternalApiFilter(new ObjectMapper(), "", "");
        Result r = run(f, URN, "", "127.0.0.1");
        assertFalse(r.passed());
        assertEquals(403, r.status());
    }

    @Test
    void internalPath_cidrTrusted_allowsAndRejects() throws Exception {
        InternalApiFilter f = new InternalApiFilter(new ObjectMapper(), "secret", "172.18.0.0/16,127.0.0.1");
        assertTrue(run(f, URN, "secret", "172.18.5.9").passed());
        assertTrue(run(f, URN, "secret", "127.0.0.1").passed());
        Result denied = run(f, URN, "secret", "10.1.2.3");
        assertFalse(denied.passed());
        assertEquals(403, denied.status());
    }
}
