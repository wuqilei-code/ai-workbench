package com.aiworkbench.common.audit;

import java.util.regex.Pattern;

/**
 * 审计/持久化日志的敏感信息脱敏。
 * <p>
 * 审计记录(可在管理端查看)可能包含异常消息或参数文本,若其中带上密钥/口令/令牌,
 * 会造成经审计表二次泄露。这里对常见形态做兜底掩码;服务端排障用日志不走此处。
 */
public final class SecretRedactor {

    private SecretRedactor() {
    }

    private static final String MASK = "***";

    private record Rule(Pattern pattern, String replacement) {
    }

    private static final Rule[] RULES = {
            // Authorization: Bearer xxx
            new Rule(Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._\\-]+"), "$1" + MASK),
            // sk-xxxx 形式的 API Key
            new Rule(Pattern.compile("(sk-[A-Za-z0-9]{4})[A-Za-z0-9_-]+"), "$1" + MASK),
            // JDBC URL 中的 password=...
            new Rule(Pattern.compile("(?i)(password=)[^&;\\s]+"), "$1" + MASK),
            // key/password/secret/token 赋值(JSON/query/文本)
            new Rule(Pattern.compile("(?i)(api[_-]?key|apikey|password|passwd|pwd|secret|token|authorization)"
                    + "(\\s*[\"']?\\s*[:=]\\s*[\"']?)([^\"'&\\s,}]+)"), "$1$2" + MASK),
    };

    /** 对文本做脱敏;null 原样返回。 */
    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        for (Rule r : RULES) {
            out = r.pattern().matcher(out).replaceAll(r.replacement());
        }
        return out;
    }

    /** 脱敏 + 截断(避免超长文本入库)。 */
    public static String maskAndTruncate(String text, int max) {
        String masked = mask(text);
        if (masked == null) {
            return null;
        }
        return masked.length() > max ? masked.substring(0, max) : masked;
    }
}
