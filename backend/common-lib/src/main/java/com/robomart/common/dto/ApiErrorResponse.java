package com.robomart.common.dto;

import java.time.Instant;

import org.springframework.http.ProblemDetail;

/** RFC 9457 response with the error envelope retained for existing clients. */
public final class ApiErrorResponse extends ProblemDetail {

    private final ErrorDetail error;
    private final String traceId;
    private final Instant timestamp;

    public ApiErrorResponse(ErrorDetail error, String traceId, Instant timestamp, int status) {
        super(status);
        this.error = error;
        this.traceId = traceId;
        this.timestamp = timestamp;
        setDetail(error.message());
        setProperty("code", error.code());
        setProperty("traceId", traceId);
        setProperty("timestamp", timestamp);
        setProperty("error", error);
    }

    public ErrorDetail error() {
        return error;
    }

    public String traceId() {
        return traceId;
    }

    public Instant timestamp() {
        return timestamp;
    }
}
