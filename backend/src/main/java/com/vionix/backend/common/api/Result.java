package com.vionix.backend.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.vionix.backend.common.trace.TraceIds;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Result<T>(
        String code,
        String message,
        T data,
        String traceId
) {
    public static <T> Result<T> ok(T data) {
        return new Result<>(ErrorCode.OK.code(), ErrorCode.OK.defaultMessage(), data, TraceIds.current());
    }

    public static Result<Void> ok() {
        return ok(null);
    }

    public static Result<Void> error(ErrorCode errorCode) {
        return error(errorCode, errorCode.defaultMessage());
    }

    public static Result<Void> error(ErrorCode errorCode, String message) {
        return new Result<>(errorCode.code(), message, null, TraceIds.current());
    }
}
