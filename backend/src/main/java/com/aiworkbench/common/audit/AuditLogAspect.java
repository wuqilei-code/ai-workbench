package com.aiworkbench.common.audit;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

/**
 * 审计切面:环绕 @AuditLog 方法,记录 userId/username/action/target/ip/method/path/status/耗时/异常。
 * userId/username 从 SecurityContext 取(JwtAuthFilter 把 userId 作 principal、username 作 credentials)。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AuditLogAspect {

    private final AuditLogger auditLogger;
    private final ExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer pnd = new DefaultParameterNameDiscoverer();

    @Around("@annotation(auditLog)")
    public Object around(ProceedingJoinPoint pjp, AuditLog auditLog) throws Throwable {
        long start = System.currentTimeMillis();
        String status = "success";
        String errorMsg = null;
        try {
            return pjp.proceed();
        } catch (Throwable t) {
            status = "fail";
            errorMsg = t.getMessage();
            throw t;
        } finally {
            long cost = System.currentTimeMillis() - start;
            try {
                record(pjp, auditLog, status, cost, errorMsg);
            } catch (Exception e) {
                log.warn("audit record failed: {}", e.getMessage());
            }
        }
    }

    private void record(ProceedingJoinPoint pjp, AuditLog auditLog, String status, long cost, String errorMsg) {
        Long userId = null;
        String username = null;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Long id) {
            userId = id;
            Object cred = auth.getCredentials();
            username = cred instanceof String s ? s : null;
        }

        String target = null;
        if (!auditLog.targetSpel().isEmpty()) {
            target = evalSpel(auditLog.targetSpel(), pjp);
        }

        String ip = null, method = null, path = null;
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            HttpServletRequest req = attrs.getRequest();
            ip = clientIp(req);
            method = req.getMethod();
            path = req.getRequestURI();
        }
        auditLogger.log(userId, username, auditLog.action(), target, ip, method, path, status, cost, errorMsg);
    }

    private String evalSpel(String expr, ProceedingJoinPoint pjp) {
        try {
            MethodSignature ms = (MethodSignature) pjp.getSignature();
            Method method = ms.getMethod();
            String[] paramNames = pnd.getParameterNames(method);
            Object[] args = pjp.getArgs();
            EvaluationContext ctx = SimpleEvaluationContext.forReadOnlyDataBinding().build();
            if (paramNames != null) {
                for (int i = 0; i < paramNames.length; i++) {
                    ctx.setVariable(paramNames[i], args[i]);
                }
            }
            Expression e = parser.parseExpression(expr);
            Object v = e.getValue(ctx);
            return v == null ? null : v.toString();
        } catch (Exception ex) {
            log.warn("audit targetSpel eval failed: {}", ex.getMessage());
            return null;
        }
    }

    private String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) {
            return xff.split(",")[0].trim();
        }
        String real = req.getHeader("X-Real-IP");
        if (real != null && !real.isEmpty()) return real;
        return req.getRemoteAddr();
    }
}
