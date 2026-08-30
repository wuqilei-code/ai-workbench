package com.aiworkbench.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<R<Void>> handleBusiness(BusinessException e) {
        return ResponseEntity.status(mapHttpStatus(e.getCode())).body(R.fail(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<R<Void>> handleValidation(MethodArgumentNotValidException e) {
        FieldError fe = e.getBindingResult().getFieldError();
        String msg = fe != null ? fe.getField() + ": " + fe.getDefaultMessage() : "validation failed";
        return ResponseEntity.badRequest().body(R.fail(ResultCode.BAD_REQUEST.getCode(), msg));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<R<Void>> handleAccessDenied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(R.fail(ResultCode.FORBIDDEN));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<R<Void>> handleAuth(AuthenticationException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(R.fail(ResultCode.UNAUTHORIZED));
    }

    /**
     * SSE 客户端断开(Broken pipe):静默忽略。
     * <p>
     * 不返回 ResponseEntity —— 响应已是 text/event-stream,写 R 对象会因无 converter
     * 触发 HttpMessageNotWritableException 二次异常,污染日志并可能影响连接清理。
     * SseEmitter 的 onError/onCompletion 回调会处理 emitter 清理。
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncNotUsable(AsyncRequestNotUsableException e) {
        log.debug("SSE client disconnected: {}", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleOther(Exception e) {
        // 服务端打全量日志便于排障;对外只返回脱敏后的通用文案,避免泄漏 SQL/路径/堆栈等内部信息。
        log.error("unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(R.fail(ResultCode.INTERNAL_ERROR.getCode(),
                        "服务器内部错误,请稍后重试或联系管理员"));
    }

    private HttpStatus mapHttpStatus(int code) {
        if (code == 401 || code == ResultCode.UNAUTHORIZED.getCode() || code == ResultCode.BAD_CREDENTIALS.getCode())
            return HttpStatus.UNAUTHORIZED;
        if (code == 403 || code == ResultCode.FORBIDDEN.getCode()) return HttpStatus.FORBIDDEN;
        if (code == 404 || code == ResultCode.SESSION_NOT_FOUND.getCode()) return HttpStatus.NOT_FOUND;
        if (code == 409 || code == ResultCode.USERNAME_EXISTS.getCode() || code == ResultCode.EMAIL_EXISTS.getCode())
            return HttpStatus.CONFLICT;
        return HttpStatus.BAD_REQUEST;
    }
}
