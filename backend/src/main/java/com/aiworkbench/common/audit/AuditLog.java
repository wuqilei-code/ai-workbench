package com.aiworkbench.common.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注需要审计的 controller 方法,由 {@link AuditLogAspect} 环绕记录。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditLog {
    /** 操作描述,如 "创建会话" */
    String action();

    /** 目标 SpEL 表达式,如 "#id";为空则不记录目标 */
    String targetSpel() default "";
}
