package com.robomart.order.grpc;

import java.lang.reflect.Method;

import org.springframework.resilience.retry.MethodRetryPredicate;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

/** Separates definitive application rejections from transport failures. */
public final class GrpcStatusClassifier implements MethodRetryPredicate {

    public static boolean isBusinessError(Status.Code code) {
        return code == Status.Code.INVALID_ARGUMENT || code == Status.Code.NOT_FOUND
                || code == Status.Code.ALREADY_EXISTS || code == Status.Code.FAILED_PRECONDITION
                || code == Status.Code.PERMISSION_DENIED;
    }

    @Override
    public boolean shouldRetry(Method method, Throwable failure) {
        if (!(failure instanceof StatusRuntimeException grpc)) {
            return false;
        }
        Status.Code code = grpc.getStatus().getCode();
        return code == Status.Code.UNAVAILABLE || code == Status.Code.RESOURCE_EXHAUSTED
                || code == Status.Code.ABORTED;
    }
}
