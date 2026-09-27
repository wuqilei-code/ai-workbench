package com.aiworkbench.common;

import lombok.Getter;

@Getter
public enum ResultCode {
    OK(0, "ok"),
    BAD_REQUEST(400, "bad request"),
    UNAUTHORIZED(401, "unauthorized"),
    FORBIDDEN(403, "forbidden"),
    NOT_FOUND(404, "not found"),
    METHOD_NOT_ALLOWED(405, "method not allowed"),
    CONFLICT(409, "conflict"),
    PAYLOAD_TOO_LARGE(413, "payload too large"),
    UNSUPPORTED_MEDIA_TYPE(415, "unsupported media type"),
    TOO_MANY_REQUESTS(429, "too many requests"),
    INTERNAL_ERROR(500, "internal error"),
    REGISTRATION_DISABLED(4003, "registration disabled"),
    USERNAME_EXISTS(4009, "username already exists"),
    EMAIL_EXISTS(4010, "email already exists"),
    BAD_CREDENTIALS(4011, "bad credentials"),
    OPENCODE_UNAVAILABLE(5001, "opencode unavailable"),
    SESSION_NOT_FOUND(4004, "session not found"),
    USER_NOT_FOUND(4012, "user not found"),
    CANNOT_OPERATE_SELF(4013, "cannot operate on yourself"),
    LAST_ADMIN(4014, "cannot demote or disable the last admin");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
