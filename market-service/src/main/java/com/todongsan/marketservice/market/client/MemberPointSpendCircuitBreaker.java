package com.todongsan.marketservice.market.client;

import com.todongsan.marketservice.global.exception.CustomException;
import com.todongsan.marketservice.global.exception.errorcode.CommonErrorCode;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 예측 참여 차감(Market → Member-Point spend) 서킷 브레이커 (docs/architecture/SPEND_CIRCUIT_BREAKER.md §2).
 *
 * 차감 호출만 감싸지 않고 예측 생성(TX1) 전에 허가를 받는다(B2). 거절되면 DB도 MP도 건드리지 않고 503으로 끝난다(B3).
 * 결과는 차감 호출 시간만으로 기록한다. 타임아웃·5xx·결과 불명은 실패, 잔액 부족 등 MP의 업무 응답은 성공이다(B4).
 * 기록하지 않고 끝난 허가(TX1 실패, 예상 밖 예외)는 돌려준다. HALF_OPEN의 시험 호출 자리를 잡아 두지 않기 위해서다.
 */
@Slf4j
@Component
public class MemberPointSpendCircuitBreaker {

    static final String INSTANCE_NAME = "memberPointSpend";

    private final CircuitBreaker circuitBreaker;
    private final boolean enabled;

    public MemberPointSpendCircuitBreaker(
            CircuitBreakerRegistry registry,
            @Value("${market.member-point.spend-circuit-breaker-enabled:true}") boolean enabled
    ) {
        this.circuitBreaker = registry.circuitBreaker(INSTANCE_NAME);
        this.enabled = enabled;
        circuitBreaker.getEventPublisher().onStateTransition(event ->
                log.warn("Member-Point spend circuit breaker state transition: {}", event.getStateTransition()));
    }

    /** 허가가 없으면 CustomException(EXTERNAL_SERVICE_UNAVAILABLE, 503). 받은 허가는 반드시 {@link Permit#close()}로 끝낸다. */
    public Permit acquire() {
        if (!enabled) {
            return Permit.NOOP;
        }
        if (!circuitBreaker.tryAcquirePermission()) {
            throw new CustomException(CommonErrorCode.EXTERNAL_SERVICE_UNAVAILABLE);
        }
        return new Permit(circuitBreaker);
    }

    public static class Permit implements AutoCloseable {

        static final Permit NOOP = new Permit(null);

        private final CircuitBreaker circuitBreaker;
        private long startedAt;
        private boolean finished;

        private Permit(CircuitBreaker circuitBreaker) {
            this.circuitBreaker = circuitBreaker;
        }

        /** 차감 호출 직전. 기록하는 시간은 여기부터다 (TX1 시간은 넣지 않는다) */
        public void callStarted() {
            startedAt = System.nanoTime();
        }

        /** MP가 응답했다 (성공 또는 잔액 부족 같은 업무 실패) */
        public void responded() {
            if (circuitBreaker != null && !finished) {
                circuitBreaker.onSuccess(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
            }
            finished = true;
        }

        /** 타임아웃·5xx·연결 실패·결과 불명 */
        public void failed(RuntimeException e) {
            if (circuitBreaker != null && !finished) {
                circuitBreaker.onError(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS, e);
            }
            finished = true;
        }

        /** 결과를 기록하지 않았으면 허가를 돌려준다 */
        @Override
        public void close() {
            if (circuitBreaker != null && !finished) {
                circuitBreaker.releasePermission();
            }
            finished = true;
        }
    }
}
