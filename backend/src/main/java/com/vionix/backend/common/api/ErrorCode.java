package com.vionix.backend.common.api;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    OK("OK", HttpStatus.OK, "success"),
    BAD_REQUEST("BAD_REQUEST", HttpStatus.BAD_REQUEST, "bad request"),
    UNAUTHORIZED("UNAUTHORIZED", HttpStatus.UNAUTHORIZED, "unauthorized"),
    FORBIDDEN("FORBIDDEN", HttpStatus.FORBIDDEN, "permission denied"),
    NOT_FOUND("NOT_FOUND", HttpStatus.NOT_FOUND, "resource not found"),
    CONFLICT("CONFLICT", HttpStatus.CONFLICT, "resource conflict"),
    DEVICE_DISABLED("DEVICE_DISABLED", HttpStatus.CONFLICT, "device disabled"),
    TENANT_MISMATCH("TENANT_MISMATCH", HttpStatus.FORBIDDEN, "tenant mismatch"),
    INTERNAL_ERROR("INTERNAL_ERROR", HttpStatus.INTERNAL_SERVER_ERROR, "internal error");

    private final String code;
    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(String code, HttpStatus status, String defaultMessage) {
        this.code = code;
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
