package com.aiworkbench.common;

import lombok.extern.slf4j.Slf4j;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;

/**
 * 全局异常处理:把各类异常统一映射为 {@link R} JSON + 恰当的 HTTP 状态码。
 * <p>
 * 约定:
 * <ul>
 *   <li>业务异常 {@link BusinessException}:按其错误码映射 HTTP 状态;</li>
 *   <li>客户端错误(参数/请求体/方法/媒体类型/路径不存在/上传过大):返回 4xx,记 debug/warn,不打 error 堆栈;</li>
 *   <li>认证/鉴权:401 / 403;</li>
 *   <li>SSE 客户端断开:静默忽略;</li>
 *   <li>其余未预期异常:500 + 全量日志(对外脱敏文案)。</li>
 * </ul>
 */
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

    /** 请求体无法解析(JSON 语法/类型错误)。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<R<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        log.debug("malformed request body: {}", e.getMessage());
        return ResponseEntity.badRequest().body(R.fail(ResultCode.BAD_REQUEST.getCode(), "请求体格式错误"));
    }

    /** 缺少必填参数 / 参数类型不匹配。 */
    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<R<Void>> handleBadRequest(Exception e) {
        log.debug("bad request parameter: {}", e.getMessage());
        return ResponseEntity.badRequest().body(R.fail(ResultCode.BAD_REQUEST.getCode(), e.getMessage()));
    }

    /** 请求方法不支持(如对只读接口发 POST)。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<R<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(R.fail(ResultCode.METHOD_NOT_ALLOWED.getCode(), e.getMessage()));
    }

    /** 请求的 Content-Type 不支持。 */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<R<Void>> handleMediaType(HttpMediaTypeNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(R.fail(ResultCode.UNSUPPORTED_MEDIA_TYPE.getCode(), e.getMessage()));
    }

    /** 上传文件超过大小限制。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<R<Void>> handleUploadSize(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(R.fail(ResultCode.PAYLOAD_TOO_LARGE.getCode(), "上传文件过大"));
    }

    /** 未知路径(无对应 handler / 静态资源):404 而非 500。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<R<Void>> handleNoResource(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(R.fail(ResultCode.NOT_FOUND));
    }

    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
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

    /**
     * 客户端断连(SSE/下载/普通响应写出时的 Broken pipe / Connection reset):
     * 响应通常已经提交,不能再写 R JSON(会触发 text/event-stream 无 converter 的二次异常)。
     * 这里只记 debug;非断连 IO 异常仍记 error,并在响应未提交时返回 500。
     */
    @ExceptionHandler(IOException.class)
    public void handleIo(IOException e, HttpServletResponse response) throws IOException {
        String msg = String.valueOf(e.getMessage());
        boolean disconnected = msg.contains("Broken pipe")
                || msg.contains("Connection reset")
                || msg.contains("你的主机中的软件中止了一个已建立的连接")
                || response.isCommitted();
        if (disconnected) {
            log.debug("client disconnected while writing response: {}", msg);
            return;
        }
        log.error("unhandled IO exception", e);
        if (!response.isCommitted()) {
            response.sendError(HttpStatus.INTERNAL_SERVER_ERROR.value());
        }
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleOther(Exception e) {
        // 服务端打全量日志便于排障;对外只返回脱敏后的通用文案,避免泄漏 SQL/路径/堆栈等内部信息。
        log.error("unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(R.fail(ResultCode.INTERNAL_ERROR.getCode(),
                        "服务器内部错误,请稍后重试或联系管理员"));
    }

    /** 业务错误码 → HTTP 状态码(未知业务码归为 400)。 */
    private HttpStatus mapHttpStatus(int code) {
        if (code == 429 || code == ResultCode.TOO_MANY_REQUESTS.getCode()) return HttpStatus.TOO_MANY_REQUESTS;
        if (code == 405) return HttpStatus.METHOD_NOT_ALLOWED;
        if (code == 413) return HttpStatus.PAYLOAD_TOO_LARGE;
        if (code == 415) return HttpStatus.UNSUPPORTED_MEDIA_TYPE;
        if (code == 401 || code == ResultCode.UNAUTHORIZED.getCode() || code == ResultCode.BAD_CREDENTIALS.getCode())
            return HttpStatus.UNAUTHORIZED;
        if (code == 403 || code == ResultCode.FORBIDDEN.getCode()) return HttpStatus.FORBIDDEN;
        if (code == 404 || code == ResultCode.NOT_FOUND.getCode()
                || code == ResultCode.SESSION_NOT_FOUND.getCode()
                || code == ResultCode.USER_NOT_FOUND.getCode()) return HttpStatus.NOT_FOUND;
        if (code == 409 || code == ResultCode.CONFLICT.getCode()
                || code == ResultCode.USERNAME_EXISTS.getCode()
                || code == ResultCode.EMAIL_EXISTS.getCode()
                || code == ResultCode.LAST_ADMIN.getCode()) return HttpStatus.CONFLICT;
        return HttpStatus.BAD_REQUEST;
    }
}
