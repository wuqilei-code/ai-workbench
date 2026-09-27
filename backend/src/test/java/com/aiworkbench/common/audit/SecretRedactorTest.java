package com.aiworkbench.common.audit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** SecretRedactor 脱敏规则单元测试(无 Spring 上下文)。 */
class SecretRedactorTest {

    @Test
    void masks_api_key_assignments() {
        assertEquals("{\"apiKey\":\"***\"}", SecretRedactor.mask("{\"apiKey\":\"abcdef123\"}"));
        assertFalse(SecretRedactor.mask("password=secret123").contains("secret123"));
    }

    @Test
    void masks_bearer_and_sk() {
        assertFalse(SecretRedactor.mask("Authorization: Bearer eyJabc.def.ghi").contains("eyJabc"));
        assertFalse(SecretRedactor.mask("key=sk-abcd1234efgh").contains("1234efgh"));
    }

    @Test
    void masks_jdbc_password() {
        String out = SecretRedactor.mask("jdbc:mysql://h:3306/db?user=u&password=p@ss;x=1");
        assertFalse(out.contains("p@ss"));
        assertTrue(out.contains("password=***"));
    }

    @Test
    void leaves_benign_text_unchanged() {
        assertEquals("admin 1 reset password for user 2 (token_version bumped)",
                SecretRedactor.mask("admin 1 reset password for user 2 (token_version bumped)"));
        assertEquals("发送消息", SecretRedactor.mask("发送消息"));
    }

    @Test
    void null_and_truncate() {
        assertNull(SecretRedactor.mask(null));
        assertEquals("ab", SecretRedactor.maskAndTruncate("ab", 5));
        assertEquals(5, SecretRedactor.maskAndTruncate("abcdef", 5).length());
    }
}
