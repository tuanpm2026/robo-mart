package com.robomart.e2e;

import com.robomart.proto.payment.*;
import com.robomart.proto.saga.*;

import io.grpc.*;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test-owned transparent proxy. The real Payment commits before its first charge reply is withheld.
 */
final class PaymentReplyBarrier implements AutoCloseable {
    private static final Context.Key<String> AUTH = Context.key("proxy-auth"),
            INSTANCE = Context.key("proxy-instance");
    final CountDownLatch committed = new CountDownLatch(1), release = new CountDownLatch(1);
    final AtomicReference<String> dispatcher = new AtomicReference<>();
    private final AtomicBoolean first = new AtomicBoolean(true);
    private final ManagedChannel upstream =
            ManagedChannelBuilder.forAddress("127.0.0.1", 19095).usePlaintext().build();
    private final Server server;

    PaymentReplyBarrier() throws java.io.IOException {
        server =
                ServerBuilder.forPort(19096)
                        .intercept(
                                new ServerInterceptor() {
                                    public <Q, R> ServerCall.Listener<Q> interceptCall(
                                            ServerCall<Q, R> call,
                                            Metadata headers,
                                            ServerCallHandler<Q, R> next) {
                                        Context context =
                                                Context.current()
                                                        .withValues(
                                                                AUTH,
                                                                headers.get(
                                                                        Metadata.Key.of(
                                                                                "authorization",
                                                                                Metadata
                                                                                        .ASCII_STRING_MARSHALLER)),
                                                                INSTANCE,
                                                                headers.get(
                                                                        Metadata.Key.of(
                                                                                "x-saga-instance",
                                                                                Metadata
                                                                                        .ASCII_STRING_MARSHALLER)));
                                        return Contexts.interceptCall(context, call, headers, next);
                                    }
                                })
                        .addService(
                                new PaymentServiceGrpc.PaymentServiceImplBase() {
                                    public void processPayment(
                                            ProcessPaymentRequest q,
                                            StreamObserver<ProcessPaymentResponse> o) {
                                        try {
                                            var receipt =
                                                    PaymentServiceGrpc.newBlockingStub(upstream)
                                                            .withDeadlineAfter(4, TimeUnit.SECONDS)
                                                            .processPayment(q);
                                            if (first.compareAndSet(true, false)) {
                                                dispatcher.set(INSTANCE.get());
                                                committed.countDown();
                                                release.await(45, TimeUnit.SECONDS);
                                            }
                                            reply(o, receipt);
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                            o.onError(Status.CANCELLED.asRuntimeException());
                                        } catch (RuntimeException e) {
                                            o.onError(e);
                                        }
                                    }
                                })
                        .addService(
                                new PaymentSagaParticipantServiceGrpc
                                        .PaymentSagaParticipantServiceImplBase() {
                                    public void getChargeOutcome(
                                            GetChargeOutcomeRequest q,
                                            StreamObserver<GetChargeOutcomeResponse> o) {
                                        forward(o, () -> outcomes().getChargeOutcome(q));
                                    }

                                    public void closeCharge(
                                            CloseChargeRequest q,
                                            StreamObserver<CloseChargeResponse> o) {
                                        forward(o, () -> outcomes().closeCharge(q));
                                    }

                                    public void getRefundOutcome(
                                            GetRefundOutcomeRequest q,
                                            StreamObserver<GetRefundOutcomeResponse> o) {
                                        forward(o, () -> outcomes().getRefundOutcome(q));
                                    }

                                    public void refundRemaining(
                                            RefundRemainingRequest q,
                                            StreamObserver<RefundRemainingResponse> o) {
                                        forward(o, () -> outcomes().refundRemaining(q));
                                    }
                                })
                        .build()
                        .start();
    }

    private PaymentSagaParticipantServiceGrpc.PaymentSagaParticipantServiceBlockingStub outcomes() {
        var headers = new Metadata();
        String authorization = AUTH.get();
        if (authorization != null)
            headers.put(
                    Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                    authorization);
        return PaymentSagaParticipantServiceGrpc.newBlockingStub(upstream)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                .withDeadlineAfter(4, TimeUnit.SECONDS);
    }

    private static <T> void forward(StreamObserver<T> o, java.util.function.Supplier<T> operation) {
        try {
            reply(o, operation.get());
        } catch (RuntimeException e) {
            o.onError(e);
        }
    }

    private static <T> void reply(StreamObserver<T> o, T value) {
        o.onNext(value);
        o.onCompleted();
    }

    public void close() {
        release.countDown();
        server.shutdownNow();
        upstream.shutdownNow();
    }
}
