package com.todongsan.insightreputation.global.client;

import com.todongsan.insightreputation.global.exception.CustomException;
import com.todongsan.insightreputation.global.exception.errorcode.ErrorCode;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Protects the read-only Insight -> Market basic-info call. Retry is deliberately
 * absent: this component must not be reused for point or other write operations.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketClientResilienceExecutor {

    private static final String INSTANCE_NAME = "marketClient";

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final BulkheadRegistry bulkheadRegistry;

    @Value("${todongsan.resilience.market-client.enabled:false}")
    private boolean enabled;

    @PostConstruct
    void registerStateTransitionLog() {
        circuitBreakerRegistry.circuitBreaker(INSTANCE_NAME)
                .getEventPublisher()
                .onStateTransition(event -> log.info("Market circuit breaker state transition: {}", event));
    }

    public <T> T execute(Supplier<T> remoteCall) {
        if (!enabled) {
            return remoteCall.get();
        }

        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(INSTANCE_NAME);
        Bulkhead bulkhead = bulkheadRegistry.bulkhead(INSTANCE_NAME);
        try {
            return Bulkhead.decorateSupplier(
                    bulkhead,
                    CircuitBreaker.decorateSupplier(circuitBreaker, remoteCall)
            ).get();
        } catch (CallNotPermittedException e) {
            log.warn("Market call rejected before downstream I/O: type={}", e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.EXTERNAL_SERVICE_UNAVAILABLE);
        } catch (BulkheadFullException e) {
            log.warn("Market call rejected before downstream I/O: type={}", e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.EXTERNAL_SERVICE_UNAVAILABLE);
        }
    }
}
