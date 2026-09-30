package com.todongsan.marketservice.market.debug;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * Opt-in instrumentation for the prediction deadlock reproduction only.
 * It neither retries nor changes transaction behaviour.
 */
@Slf4j
@Component
public class MarketPredictionDeadlockDebug {

    public static final String REQUEST_ID_HEADER = "X-Benchmark-Request-Id";
    private static final String REQUEST_ID_MDC_KEY = "benchmarkRequestId";
    private static final String MARKET_ID_MDC_KEY = "benchmarkMarketId";
    private static final String MEMBER_ID_MDC_KEY = "benchmarkMemberId";

    private final boolean enabled;
    private final Map<String, String> failuresByRequestId = new ConcurrentHashMap<>();

    public MarketPredictionDeadlockDebug(
            @Value("${todongsan.benchmark.deadlock-debug.enabled:false}") boolean enabled
    ) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    public void requestStart(long marketId, Long memberId) {
        if (!active()) {
            return;
        }
        MDC.put(MARKET_ID_MDC_KEY, String.valueOf(marketId));
        MDC.put(MEMBER_ID_MDC_KEY, String.valueOf(memberId));
        phase("REQUEST_START", null);
    }

    public void requestEnd(int status) {
        if (!active()) {
            return;
        }
        phase("REQUEST_END status=" + status, null);
    }

    public void phase(String phase, Long predictionId) {
        if (!active()) {
            return;
        }
        log.info(
                "DEADLOCK_PHASE requestId={} thread={} marketId={} memberId={} predictionId={} phase={}",
                MDC.get(REQUEST_ID_MDC_KEY),
                Thread.currentThread().getName(),
                MDC.get(MARKET_ID_MDC_KEY),
                MDC.get(MEMBER_ID_MDC_KEY),
                predictionId,
                phase
        );
    }

    public void recordUnhandledException(Exception exception) {
        if (!active()) {
            return;
        }
        failuresByRequestId.put(MDC.get(REQUEST_ID_MDC_KEY), classify(exception));
    }

    public String takeFailureCategory(String requestId) {
        return failuresByRequestId.remove(requestId);
    }

    public void reset() {
        failuresByRequestId.clear();
    }

    public static void bindRequestId(String requestId) {
        MDC.put(REQUEST_ID_MDC_KEY, requestId);
    }

    public static void clearRequestContext() {
        MDC.remove(REQUEST_ID_MDC_KEY);
        MDC.remove(MARKET_ID_MDC_KEY);
        MDC.remove(MEMBER_ID_MDC_KEY);
    }

    private boolean active() {
        return enabled && MDC.get(REQUEST_ID_MDC_KEY) != null;
    }

    private String classify(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof DeadlockLoserDataAccessException) {
                return "DEADLOCK";
            }
            if (current instanceof DuplicateKeyException) {
                return "DUPLICATE";
            }
        }
        return "HTTP_500_OTHER";
    }
}
