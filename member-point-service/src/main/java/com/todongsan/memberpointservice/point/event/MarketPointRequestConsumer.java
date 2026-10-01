package com.todongsan.memberpointservice.point.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.todongsan.memberpointservice.point.dto.response.BatchItemResult;
import com.todongsan.memberpointservice.point.entity.PointHistoryType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Market 포인트 적립 요청을 배치 단위로 처리한다. 업무 흐름마다 토픽이 다르다 (market.refund-requests, market.settlement-payout-requests).
 * 구조는 Market 결과 consumer와 같다 (ASYNC_POINT_FLOW.md §16.1, §17).
 *
 * ① 파싱·타입 검증 → 처리 불가 건 분리
 * ② 배치 트랜잭션 1개로 적립 (실패 시 배치 재시도, 그래도 실패하면 건 단위 경로로 fallback해 원인 건 격리)
 * ③ 처리 불가 건이나 원인 건이 있으면 가장 앞선 위치를 BatchListenerFailedException 하위 타입으로 알린다.
 *    error handler가 그 앞 건의 offset을 확정하고, 그 건을 기존 정책(NonRetryable 즉시, 그 외 재시도 후 DLT)으로 처리한다.
 *    실패 직후 DB 상태 확인까지 실패하면 DatabaseUnavailableException으로 알려 DLT 대신 복구를 기다린다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "member-point.kafka.market-point-requests", name = "enabled", havingValue = "true")
public class MarketPointRequestConsumer {

    static final int BATCH_ATTEMPTS = 3;
    private static final long[] BATCH_RETRY_BACKOFF_MS = {200L, 500L};
    private static final int SUPPORTED_SCHEMA_VERSION = 1;
    static final String MARKET_REFUND_REQUESTED = "MARKET_REFUND_REQUESTED";
    static final String MARKET_SETTLEMENT_PAYOUT_REQUESTED = "MARKET_SETTLEMENT_PAYOUT_REQUESTED";

    private final ObjectMapper objectMapper;
    private final MarketPointCreditBatchApplier batchApplier;
    private final MarketPointRequestService marketPointRequestService;
    private final JdbcTemplate jdbcTemplate;

    /** 토픽마다 listener container가 따로라 처리 thread도 따로다. 대량 정산 지급이 환불을 지연시키지 않는다. */
    @KafkaListener(topics = "${member-point.kafka.market-point-requests.refund-topic}", batch = "true")
    public void consumeRefund(List<ConsumerRecord<String, String>> records) {
        consume(records, MARKET_REFUND_REQUESTED, PointHistoryType.REFUND_MARKET);
    }

    @KafkaListener(topics = "${member-point.kafka.market-point-requests.settlement-payout-topic}", batch = "true")
    public void consumeSettlementPayout(List<ConsumerRecord<String, String>> records) {
        consume(records, MARKET_SETTLEMENT_PAYOUT_REQUESTED, PointHistoryType.SETTLE_MARKET);
    }

    private void consume(List<ConsumerRecord<String, String>> records, String expectedEventType, PointHistoryType type) {
        Failure firstFailure = null;
        List<Integer> indexes = new ArrayList<>();
        List<MarketPointCreditBatchApplier.Item> items = new ArrayList<>();
        for (int i = 0; i < records.size(); i++) {
            try {
                MarketPointRequestEvent event = parse(records.get(i).value());
                validateType(event, expectedEventType, records.get(i).topic());
                items.add(new MarketPointCreditBatchApplier.Item(event, type));
                indexes.add(i);
            } catch (NonRetryableEventException e) {
                firstFailure = Failure.earliest(firstFailure, new Failure(i, e));
            }
        }

        if (!items.isEmpty()) {
            Applied applied = applyWithFallback(items, indexes);
            firstFailure = Failure.earliest(firstFailure, applied.failure());
            if (applied.failure() == null || !applied.failure().databaseUnavailable()) {
                log.info("Market point request batch processed. records={}, processed={}, alreadyProcessed={}, failed={}, fallback={}",
                        records.size(), count(applied, "PROCESSED"), count(applied, "ALREADY_PROCESSED"),
                        count(applied, "FAILED"), applied.fallback());
            }
        }

        if (firstFailure != null) {
            throw firstFailure.toException();
        }
    }

    /**
     * 배치 트랜잭션 → 재시도 → 건 단위 fallback. 건 단위 경로는 배치 이전의 검증된 처리(MarketPointRequestService.process)다.
     * 실패할 때마다 DB 상태를 확인하고, DB가 죽어 있으면 남은 시도를 멈추고 배치 첫 건부터 장애 대기로 넘긴다.
     */
    private Applied applyWithFallback(List<MarketPointCreditBatchApplier.Item> items, List<Integer> indexes) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= BATCH_ATTEMPTS; attempt++) {
            try {
                return new Applied(batchApplier.applyBatch(items), null, false);
            } catch (RuntimeException e) {
                last = e;
                log.warn("Market point request batch failed. attempt={}/{}, size={}, cause={}",
                        attempt, BATCH_ATTEMPTS, items.size(), e.toString());
                if (!isDatabaseAvailable()) {
                    return new Applied(List.of(), new Failure(indexes.get(0), e, true), false);
                }
                if (attempt < BATCH_ATTEMPTS) {
                    sleep(BATCH_RETRY_BACKOFF_MS[attempt - 1]);
                }
            }
        }

        log.warn("Market point request batch falls back to per-record processing. size={}, lastCause={}", items.size(), last.toString());
        List<BatchItemResult> results = new ArrayList<>(items.size());
        for (int k = 0; k < items.size(); k++) {
            try {
                results.add(marketPointRequestService.process(items.get(k).event(), items.get(k).type()));
            } catch (RuntimeException e) {
                return new Applied(results, new Failure(indexes.get(k), e, !isDatabaseAvailable()), true);
            }
        }
        return new Applied(results, null, true);
    }

    /** DB가 살아 있는데 실패했다면 메시지 쪽 문제다. 메시지는 SELECT 1을 실패시킬 수 없다. */
    private boolean isDatabaseAvailable() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return true;
        } catch (RuntimeException probeFailure) {
            log.warn("Member-Point DB is unavailable. Market point requests wait for recovery instead of DLT. probe={}",
                    probeFailure.toString());
            return false;
        }
    }

    private MarketPointRequestEvent parse(String value) {
        try {
            MarketPointRequestEvent event = objectMapper.readValue(value, MarketPointRequestEvent.class);
            validateRequired(event);
            return event;
        } catch (JsonProcessingException e) {
            throw new NonRetryableEventException("Malformed market point request event", e);
        }
    }

    /** 요청 스키마 필수 필드. 누락은 재시도해도 해결되지 않으므로 즉시 DLT (ASYNC_POINT_FLOW.md §13.5 F2). */
    private void validateRequired(MarketPointRequestEvent event) {
        MarketPointRequestEvent.Data data = event.data();
        if (event.eventId() == null || data == null
                || data.marketId() == null || data.predictionId() == null || data.memberId() == null
                || data.amount() == null || data.referenceType() == null || data.referenceId() == null
                || data.idempotencyKey() == null) {
            throw new NonRetryableEventException("Required field is missing. eventId=" + event.eventId());
        }
    }

    /** 토픽이 곧 업무 흐름이다. 토픽과 다른 타입은 발행 측 버그이므로 재시도하지 않는다 (ASYNC_POINT_FLOW.md §8.1 E5). */
    private void validateType(MarketPointRequestEvent event, String expectedEventType, String topic) {
        if (event.schemaVersion() == null || event.schemaVersion() != SUPPORTED_SCHEMA_VERSION) {
            throw new NonRetryableEventException("Unsupported schemaVersion=" + event.schemaVersion()
                    + ", eventId=" + event.eventId());
        }
        if (!expectedEventType.equals(event.eventType())) {
            throw new NonRetryableEventException("Unexpected eventType=" + event.eventType()
                    + " for topic=" + topic + ", eventId=" + event.eventId());
        }
    }

    private static long count(Applied applied, String status) {
        return applied.results().stream().filter(result -> status.equals(result.getStatus())).count();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during batch retry backoff", e);
        }
    }

    private record Applied(List<BatchItemResult> results, Failure failure, boolean fallback) {
    }

    private record Failure(int index, Exception cause, boolean databaseUnavailable) {

        Failure(int index, Exception cause) {
            this(index, cause, false);
        }

        BatchListenerFailedException toException() {
            return databaseUnavailable
                    ? new DatabaseUnavailableException("Member-Point DB is unavailable: " + cause.getMessage(), cause, index)
                    : new PointRequestRecordFailedException(cause.getMessage(), cause, index);
        }

        static Failure earliest(Failure current, Failure candidate) {
            if (candidate == null) {
                return current;
            }
            return current == null || candidate.index() < current.index() ? candidate : current;
        }
    }
}
