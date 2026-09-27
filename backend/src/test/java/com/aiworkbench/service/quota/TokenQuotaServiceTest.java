package com.aiworkbench.service.quota;

import com.aiworkbench.common.BusinessException;
import com.aiworkbench.mapper.UserTokenDailyMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** TokenQuotaService 单元测试(mapper 用 mock,无 DB)。 */
class TokenQuotaServiceTest {

    private TokenQuotaService svc(UserTokenDailyMapper mapper, long limit) {
        TokenQuotaService s = new TokenQuotaService(mapper);
        ReflectionTestUtils.setField(s, "dailyTokens", limit);
        return s;
    }

    @Test
    void disabled_when_limit_is_zero() {
        UserTokenDailyMapper mapper = mock(UserTokenDailyMapper.class);
        TokenQuotaService s = svc(mapper, 0);
        assertFalse(s.enabled());
        s.assertWithinQuota(1L);            // 不抛
        verify(mapper, never()).selectTokens(anyLong(), any(LocalDate.class));
    }

    @Test
    void allows_under_limit() {
        UserTokenDailyMapper mapper = mock(UserTokenDailyMapper.class);
        when(mapper.selectTokens(1L, LocalDate.now())).thenReturn(40L);
        TokenQuotaService s = svc(mapper, 100);
        assertTrue(s.enabled());
        assertEquals(40L, s.todayTokens(1L));
        assertDoesNotThrow(() -> s.assertWithinQuota(1L));
    }

    @Test
    void rejects_at_or_over_limit_with_429() {
        UserTokenDailyMapper mapper = mock(UserTokenDailyMapper.class);
        when(mapper.selectTokens(1L, LocalDate.now())).thenReturn(100L);
        TokenQuotaService s = svc(mapper, 100);
        BusinessException ex = assertThrows(BusinessException.class, () -> s.assertWithinQuota(1L));
        assertEquals(429, ex.getCode());
    }

    @Test
    void addUsage_ignores_non_positive_and_failures() {
        UserTokenDailyMapper mapper = mock(UserTokenDailyMapper.class);
        TokenQuotaService s = svc(mapper, 100);
        s.addUsage(1L, 0);
        s.addUsage(1L, -5);
        verify(mapper, never()).addTokens(anyLong(), any(LocalDate.class), anyLong());

        when(mapper.addTokens(anyLong(), any(LocalDate.class), anyLong()))
                .thenThrow(new RuntimeException("db down"));
        assertDoesNotThrow(() -> s.addUsage(1L, 10)); // fail-open
    }

    @Test
    void query_failure_fails_open() {
        UserTokenDailyMapper mapper = mock(UserTokenDailyMapper.class);
        when(mapper.selectTokens(anyLong(), any(LocalDate.class))).thenThrow(new RuntimeException("db down"));
        TokenQuotaService s = svc(mapper, 100);
        assertEquals(0L, s.todayTokens(1L));
        assertDoesNotThrow(() -> s.assertWithinQuota(1L));
    }
}
