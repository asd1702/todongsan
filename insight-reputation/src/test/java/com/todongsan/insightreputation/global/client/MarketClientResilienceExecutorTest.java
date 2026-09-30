package com.todongsan.insightreputation.global.client;

import com.todongsan.insightreputation.global.exception.CustomException;
import com.todongsan.insightreputation.global.exception.errorcode.ErrorCode;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketClientResilienceExecutorTest {

    @Test
    void classifiesOnlyKnownBusinessExceptionsAsNonFailures() {
        MarketClientCircuitBreakerFailurePredicate predicate = new MarketClientCircuitBreakerFailurePredicate();

        assertThat(predicate.test(new CustomException(ErrorCode.RESOURCE_NOT_FOUND))).isFalse();
        assertThat(predicate.test(new CustomException(ErrorCode.VALIDATION_FAILED))).isFalse();
        assertThat(predicate.test(new CustomException(ErrorCode.EXTERNAL_SERVICE_UNAVAILABLE))).isTrue();
        assertThat(predicate.test(new CustomException(ErrorCode.EXTERNAL_SERVICE_TIMEOUT))).isTrue();
        assertThat(predicate.test(new CustomException(ErrorCode.EXTERNAL_SERVICE_ERROR))).isTrue();
        assertThat(predicate.test(new CustomException(ErrorCode.EXTERNAL_SERVICE_BAD_RESPONSE))).isTrue();
        assertThat(predicate.test(new IllegalStateException("unexpected client failure"))).isTrue();
    }

    @Test
    void keepsCircuitClosedForRepeatedResourceNotFound() {
        CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.of(circuitBreakerConfig());
        MarketClientResilienceExecutor executor = enabledExecutor(
                circuitBreakerRegistry,
                BulkheadRegistry.ofDefaults()
        );

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> executor.execute(() -> {
                throw new CustomException(ErrorCode.RESOURCE_NOT_FOUND);
            }))
                    .isInstanceOf(CustomException.class)
                    .extracting(error -> ((CustomException) error).getErrorCode())
                    .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        }

        assertThat(circuitBreakerRegistry.circuitBreaker("marketClient").getState())
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void opensCircuitForRepeatedUnavailableFailuresAndRejectsLaterCallsWithoutDownstreamIo() {
        CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.of(
                circuitBreakerConfig()
        );
        MarketClientResilienceExecutor executor = enabledExecutor(
                circuitBreakerRegistry,
                BulkheadRegistry.ofDefaults()
        );
        AtomicInteger downstreamCalls = new AtomicInteger();

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> executor.execute(() -> {
                downstreamCalls.incrementAndGet();
                throw new CustomException(ErrorCode.EXTERNAL_SERVICE_UNAVAILABLE);
            })).isInstanceOf(CustomException.class);
        }

        assertThat(circuitBreakerRegistry.circuitBreaker("marketClient").getState())
                .isEqualTo(CircuitBreaker.State.OPEN);
        assertThatThrownBy(() -> executor.execute(() -> {
            downstreamCalls.incrementAndGet();
            return "unexpected";
        }))
                .isInstanceOf(CustomException.class)
                .extracting(error -> ((CustomException) error).getErrorCode())
                .isEqualTo(ErrorCode.EXTERNAL_SERVICE_UNAVAILABLE);
        assertThat(downstreamCalls).hasValue(4);
    }

    @Test
    void opensCircuitForRepeatedTimeoutFailures() {
        CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.of(circuitBreakerConfig());
        MarketClientResilienceExecutor executor = enabledExecutor(
                circuitBreakerRegistry,
                BulkheadRegistry.ofDefaults()
        );

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> executor.execute(() -> {
                throw new CustomException(ErrorCode.EXTERNAL_SERVICE_TIMEOUT);
            })).isInstanceOf(CustomException.class);
        }

        assertThat(circuitBreakerRegistry.circuitBreaker("marketClient").getState())
                .isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void bypassesCircuitBreakerAndBulkheadWhenDisabled() {
        CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.of(circuitBreakerConfig());
        MarketClientResilienceExecutor executor = new MarketClientResilienceExecutor(
                circuitBreakerRegistry,
                BulkheadRegistry.of(BulkheadConfig.custom().maxConcurrentCalls(1).build())
        );
        ReflectionTestUtils.setField(executor, "enabled", false);
        AtomicInteger downstreamCalls = new AtomicInteger();

        assertThat(executor.execute(downstreamCalls::incrementAndGet)).isEqualTo(1);
        assertThat(circuitBreakerRegistry.circuitBreaker("marketClient").getState())
                .isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(downstreamCalls).hasValue(1);
    }

    @Test
    void rejectsBulkheadSaturationWithoutRecordingAnExtraDownstreamCall() throws Exception {
        MarketClientResilienceExecutor executor = enabledExecutor(
                CircuitBreakerRegistry.ofDefaults(),
                BulkheadRegistry.of(BulkheadConfig.custom()
                        .maxConcurrentCalls(1)
                        .maxWaitDuration(Duration.ZERO)
                        .build())
        );
        CountDownLatch firstCallEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstCall = new CountDownLatch(1);
        AtomicInteger downstreamCalls = new AtomicInteger();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<String> first = pool.submit(() -> executor.execute(() -> {
                downstreamCalls.incrementAndGet();
                firstCallEntered.countDown();
                try {
                    if (!releaseFirstCall.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Test timed out waiting to release first call");
                    }
                    return "first";
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }));
            assertThat(firstCallEntered.await(2, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> executor.execute(() -> {
                downstreamCalls.incrementAndGet();
                return "second";
            }))
                    .isInstanceOf(CustomException.class)
                    .extracting(error -> ((CustomException) error).getErrorCode())
                    .isEqualTo(ErrorCode.EXTERNAL_SERVICE_UNAVAILABLE);
            assertThat(downstreamCalls).hasValue(1);

            releaseFirstCall.countDown();
            assertThat(first.get(2, TimeUnit.SECONDS)).isEqualTo("first");
        } finally {
            releaseFirstCall.countDown();
            pool.shutdownNow();
        }
    }

    private MarketClientResilienceExecutor enabledExecutor(
            CircuitBreakerRegistry circuitBreakerRegistry,
            BulkheadRegistry bulkheadRegistry
    ) {
        MarketClientResilienceExecutor executor = new MarketClientResilienceExecutor(
                circuitBreakerRegistry,
                bulkheadRegistry
        );
        ReflectionTestUtils.setField(executor, "enabled", true);
        return executor;
    }

    private CircuitBreakerConfig circuitBreakerConfig() {
        return CircuitBreakerConfig.custom()
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50)
                .recordException(new MarketClientCircuitBreakerFailurePredicate())
                .build();
    }
}
