package com.aiworkbench.common;

import lombok.Getter;

@Getter
public enum ResultCode {
    OK(0, "ok"),
    BAD_REQUEST(400, "bad request"),
    UNAUTHORIZED(401, "unauthorized"),
    FORBIDDEN(403, "forbidden"),
    NOT_FOUND(404, "not found"),
    CONFLICT(409, "conflict"),
    INTERNAL_ERROR(500, "internal error"),
    REGISTRATION_DISABLED(4003, "registration disabled"),
    USERNAME_EXISTS(4009, "username already exists"),
    EMAIL_EXISTS(4010, "email already exists"),
    BAD_CREDENTIALS(4011, "bad credentials"),
    OPENCODE_UNAVAILABLE(5001, "opencode unavailable"),
    SESSION_NOT_FOUND(4004, "session not found"),
    TOO_MANY_REQUESTS(429, "too many requests");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
