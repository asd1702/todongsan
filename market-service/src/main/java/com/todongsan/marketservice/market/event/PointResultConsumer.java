package com.todongsan.marketservice.market.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.todongsan.marketservice.market.service.MarketRefundTransactionService;
import com.todongsan.marketservice.market.service.MarketSettlementTransactionService;
import com.todongsan.marketservice.market.service.PointResultApplyOutcome;
import com.todongsan.marketservice.market.service.PointResultBatchApplier;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.stereotype.Component;

/**
 * Member-Point 포인트 적립 결과(memberpoint.point-results)를 배치 단위로 환불·정산 detail에 반영한다
 * (ASYNC_POINT_FLOW.md §16.1).
 *
 * ① 파싱·검증 → 처리 불가 건 분리
 * ② 배치 트랜잭션 1개로 반영 (실패 시 배치 재시도, 그래도 실패하면 건 단위 fallback으로 원인 건 격리)
 * ③ 처리 불가 건이나 원인 건이 있으면 가장 앞선 위치를 BatchListenerFailedException으로 알린다.
 *    error handler가 그 앞 건의 offset을 확정하고, 그 건을 기존 정책(NonRetryable 즉시, 그 외 재시도 후 DLT)으로 처리한다.
 *    단, 실패 직후 DB 상태 확인까지 실패하면 DatabaseUnavailableException으로 알려 DLT 대신 복구를 기다린다.
 *    그 뒤 건은 다시 전달되어 멱등하게 재반영된다. DB 커밋이 DLT·offset 커밋보다 항상 먼저다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "market.kafka.point-results", name = "enabled", havingValue = "true")
public class PointResultConsumer {

    static final int BATCH_ATTEMPTS = 3;
    private static final long[] BATCH_RETRY_BACKOFF_MS = {200L, 500L};
    private static final int SUPPORTED_SCHEMA_VERSION = 1;
    private static final String POINT_CREDITED = "POINT_CREDITED";
    private static final String POINT_CREDIT_FAILED = "POINT_CREDIT_FAILED";

    private final ObjectMapper objectMapper;
    private final PointResultBatchApplier batchApplier;
    private final MarketRefundTransactionService refundTransactionService;
    private final MarketSettlementTransactionService settlementTransactionService;
    private final JdbcTemplate jdbcTemplate;

    @KafkaListener(topics = "${market.kafka.point-results.topic}", batch = "true")
    public void consume(List<ConsumerRecord<String, String>> records) {
        Failure firstFailure = null;
        List<Integer> indexes = new ArrayList<>();
        List<PointResultBatchApplier.Item> items = new ArrayList<>();
        for (int i = 0; i < records.size(); i++) {
            try {
                items.add(toItem(parse(records.get(i).value())));
                indexes.add(i);
            } catch (NonRetryableEventException e) {
                firstFailure = Failure.earliest(firstFailure, new Failure(i, e));
            }
        }

        if (!items.isEmpty()) {
            Applied applied = applyWithFallback(items, indexes);
            firstFailure = Failure.earliest(firstFailure, applied.failure());
            for (int k = 0; k < applied.outcomes().size(); k++) {
                if (applied.outcomes().get(k) == PointResultApplyOutcome.DETAIL_NOT_FOUND) {
                    PointResultBatchApplier.Item item = items.get(k);
                    firstFailure = Failure.earliest(firstFailure, new Failure(indexes.get(k), new NonRetryableEventException(
                            "Detail not found. requestEventType=" + item.requestEventType()
                                    + ", idempotencyKey=" + item.idempotencyKey())));
                }
            }
            if (applied.failure() == null || !applied.failure().databaseUnavailable()) {
                log.info("Point result batch applied. records={}, applied={}, skipped={}, fallback={}",
                        records.size(),
                        applied.outcomes().stream().filter(o -> o == PointResultApplyOutcome.APPLIED).count(),
                        applied.outcomes().stream().filter(o -> o == PointResultApplyOutcome.SKIPPED).count(),
                        applied.fallback());
            }
        }

        if (firstFailure != null) {
            throw firstFailure.toException();
        }
    }

    /**
     * 배치 트랜잭션 → 재시도 → 건 단위 fallback (Q3). 반환하는 outcomes는 성공한 건까지만 채워질 수 있다.
     * 실패할 때마다 DB 상태를 확인하고, DB가 죽어 있으면 남은 시도를 멈추고 배치 첫 건부터 장애 대기로 넘긴다.
     */
    private Applied applyWithFallback(List<PointResultBatchApplier.Item> items, List<Integer> indexes) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= BATCH_ATTEMPTS; attempt++) {
            try {
                return new Applied(batchApplier.applyBatch(items), null, false);
            } catch (RuntimeException e) {
                last = e;
                log.warn("Point result batch failed. attempt={}/{}, size={}, cause={}",
                        attempt, BATCH_ATTEMPTS, items.size(), e.toString());
                if (!isDatabaseAvailable()) {
                    return new Applied(List.of(), new Failure(indexes.get(0), e, true), false);
                }
                if (attempt < BATCH_ATTEMPTS) {
                    sleep(BATCH_RETRY_BACKOFF_MS[attempt - 1]);
                }
            }
        }

        log.warn("Point result batch falls back to per-record processing. size={}, lastCause={}", items.size(), last.toString());
        List<PointResultApplyOutcome> outcomes = new ArrayList<>(items.size());
        for (int k = 0; k < items.size(); k++) {
            try {
                outcomes.add(applySingle(items.get(k)));
            } catch (RuntimeException e) {
                return new Applied(outcomes, new Failure(indexes.get(k), e, !isDatabaseAvailable()), true);
            }
        }
        return new Applied(outcomes, null, true);
    }

    /** DB가 살아 있는데 실패했다면 메시지 쪽 문제다. 메시지는 SELECT 1을 실패시킬 수 없다. */
    private boolean isDatabaseAvailable() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return true;
        } catch (RuntimeException probeFailure) {
            log.warn("Market DB is unavailable. Point results wait for recovery instead of DLT. probe={}",
                    probeFailure.toString());
            return false;
        }
    }

    private PointResultApplyOutcome applySingle(PointResultBatchApplier.Item item) {
        return switch (item.requestEventType()) {
            case PointResultBatchApplier.MARKET_REFUND_REQUESTED -> refundTransactionService.applyAsyncRefundResult(
                    item.marketId(), item.idempotencyKey(), item.credited(), item.errorCode());
            case PointResultBatchApplier.MARKET_SETTLEMENT_PAYOUT_REQUESTED -> settlementTransactionService.applyAsyncSettlementResult(
                    item.marketId(), item.idempotencyKey(), item.credited(), item.errorCode());
            default -> throw new IllegalArgumentException("Unsupported requestEventType=" + item.requestEventType());
        };
    }

    private PointResultBatchApplier.Item toItem(PointResultEvent event) {
        PointResultEvent.Data data = event.data();
        String requestEventType = data.requestEventType();
        if (!PointResultBatchApplier.MARKET_REFUND_REQUESTED.equals(requestEventType)
                && !PointResultBatchApplier.MARKET_SETTLEMENT_PAYOUT_REQUESTED.equals(requestEventType)) {
            throw new NonRetryableEventException("Unsupported requestEventType=" + requestEventType
                    + ", eventId=" + event.eventId());
        }
        return new PointResultBatchApplier.Item(
                requestEventType,
                data.marketId(),
                data.idempotencyKey(),
                POINT_CREDITED.equals(event.eventType()),
                data.errorCode()
        );
    }

    private PointResultEvent parse(String value) {
        PointResultEvent event;
        try {
            event = objectMapper.readValue(value, PointResultEvent.class);
        } catch (JsonProcessingException e) {
            throw new NonRetryableEventException("Malformed point result event", e);
        }
        validate(event);
        return event;
    }

    private void validate(PointResultEvent event) {
        if (event.schemaVersion() == null || event.schemaVersion() != SUPPORTED_SCHEMA_VERSION) {
            throw new NonRetryableEventException("Unsupported schemaVersion=" + event.schemaVersion()
                    + ", eventId=" + event.eventId());
        }
        if (!POINT_CREDITED.equals(event.eventType()) && !POINT_CREDIT_FAILED.equals(event.eventType())) {
            throw new NonRetryableEventException("Unknown eventType=" + event.eventType()
                    + ", eventId=" + event.eventId());
        }
        PointResultEvent.Data data = event.data();
        if (data == null || data.marketId() == null || data.idempotencyKey() == null || data.requestEventType() == null) {
            throw new NonRetryableEventException("Required field is missing. eventId=" + event.eventId());
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during batch retry backoff", e);
        }
    }

    private record Applied(List<PointResultApplyOutcome> outcomes, Failure failure, boolean fallback) {
    }

    private record Failure(int index, Exception cause, boolean databaseUnavailable) {

        Failure(int index, Exception cause) {
            this(index, cause, false);
        }

        BatchListenerFailedException toException() {
            String message = databaseUnavailable ? "Market DB is unavailable: " + cause.getMessage() : cause.getMessage();
            return databaseUnavailable
                    ? new DatabaseUnavailableException(message, cause, index)
                    : new PointResultRecordFailedException(message, cause, index);
        }

        static Failure earliest(Failure current, Failure candidate) {
            if (candidate == null) {
                return current;
            }
            return current == null || candidate.index() < current.index() ? candidate : current;
        }
    }
}
