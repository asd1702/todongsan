package com.todongsan.memberpointservice.point.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.todongsan.memberpointservice.global.config.ConsumerDatabase;
import com.todongsan.memberpointservice.point.dto.response.BatchItemResult;
import com.todongsan.memberpointservice.point.entity.PointHistoryType;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Market 포인트 적립 요청을 배치 단위로 처리한다. 업무 흐름마다 토픽이 다르다 (market.refund-requests, market.settlement-payout-requests).
 * 구조는 Market 결과 consumer와 같다 (ASYNC_POINT_FLOW.md §16.1, §17).
 *
 * ① 파싱·타입 검증 → 처리 불가 건 분리. 정산 지급 묶음(v2, §18)은 건으로 펼친다
 * ② 건을 최대 maxBatchItems건씩 트랜잭션 1개로 적립 (실패 시 배치 재시도, 그래도 실패하면 건 단위 경로로 fallback해 원인 건 격리)
 * ③ 처리 불가 건이나 원인 건이 있으면 가장 앞선 레코드 위치를 BatchListenerFailedException 하위 타입으로 알린다.
 *    error handler가 그 앞 레코드의 offset을 확정하고, 그 레코드를 기존 정책(NonRetryable 즉시, 그 외 재시도 후 DLT)으로 처리한다.
 *    원인 건이 나온 레코드(묶음)의 나머지 건은 끝까지 처리하고, 그 뒤 레코드는 다시 전달받는다 (§18.5 F3).
 *    실패 직후 DB 상태 확인까지 실패하면 DatabaseUnavailableException으로 알려 DLT 대신 복구를 기다린다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "member-point.kafka.market-point-requests", name = "enabled", havingValue = "true")
public class MarketPointRequestConsumer {

    static final int BATCH_ATTEMPTS = 3;
    private static final long[] BATCH_RETRY_BACKOFF_MS = {200L, 500L};
    private static final int SUPPORTED_SCHEMA_VERSION = 1;
    private static final int CHUNK_SCHEMA_VERSION = 2;
    static final String MARKET_REFUND_REQUESTED = "MARKET_REFUND_REQUESTED";
    static final String MARKET_SETTLEMENT_PAYOUT_REQUESTED = "MARKET_SETTLEMENT_PAYOUT_REQUESTED";

    private final ObjectMapper objectMapper;
    private final MarketPointCreditBatchApplier batchApplier;
    private final MarketPointRequestService marketPointRequestService;
    private final ConsumerDatabase consumerDatabase;
    private final int maxBatchItems;

    public MarketPointRequestConsumer(
            ObjectMapper objectMapper,
            MarketPointCreditBatchApplier batchApplier,
            MarketPointRequestService marketPointRequestService,
            ConsumerDatabase consumerDatabase,
            @Value("${member-point.kafka.market-point-requests.max-batch-items:100}") int maxBatchItems
    ) {
        this.objectMapper = objectMapper;
        this.batchApplier = batchApplier;
        this.marketPointRequestService = marketPointRequestService;
        this.consumerDatabase = consumerDatabase;
        this.maxBatchItems = maxBatchItems;
    }

    /** 토픽마다 listener container가 따로라 처리 thread도 따로다. 대량 정산 지급이 환불을 지연시키지 않는다. */
    @KafkaListener(topics = "${member-point.kafka.market-point-requests.refund-topic}", batch = "true")
    public void consumeRefund(List<ConsumerRecord<String, String>> records) {
        consume(records, MARKET_REFUND_REQUESTED, PointHistoryType.REFUND_MARKET, false);
    }

    /** 정산 지급은 건별(v1)과 묶음(v2)을 모두 받는다 (§18.3 C4). */
    @KafkaListener(topics = "${member-point.kafka.market-point-requests.settlement-payout-topic}", batch = "true")
    public void consumeSettlementPayout(List<ConsumerRecord<String, String>> records) {
        consume(records, MARKET_SETTLEMENT_PAYOUT_REQUESTED, PointHistoryType.SETTLE_MARKET, true);
    }

    private void consume(List<ConsumerRecord<String, String>> records, String expectedEventType, PointHistoryType type,
                         boolean chunkSupported) {
        Failure firstFailure = null;
        List<Unit> units = new ArrayList<>();
        for (int i = 0; i < records.size(); i++) {
            try {
                for (MarketPointRequestEvent event : parse(records.get(i), expectedEventType, chunkSupported)) {
                    units.add(new Unit(i, new MarketPointCreditBatchApplier.Item(event, type)));
                }
            } catch (NonRetryableEventException e) {
                firstFailure = Failure.earliest(firstFailure, new Failure(i, e));
            }
        }

        Progress progress = new Progress();
        for (int from = 0; from < units.size() && !progress.stopped(); from += maxBatchItems) {
            List<Unit> group = progress.pending(units.subList(from, Math.min(from + maxBatchItems, units.size())));
            if (!group.isEmpty()) {
                applyWithFallback(group, progress);
            }
        }
        firstFailure = Failure.earliest(firstFailure, progress.failure);

        if (!units.isEmpty() && (progress.failure == null || !progress.failure.databaseUnavailable())) {
            log.info("Market point request batch processed. records={}, items={}, processed={}, alreadyProcessed={}, failed={}, fallback={}",
                    records.size(), units.size(), progress.count("PROCESSED"), progress.count("ALREADY_PROCESSED"),
                    progress.count("FAILED"), progress.fallback);
        }

        if (firstFailure != null) {
            throw firstFailure.toException();
        }
    }

    /**
     * 배치 트랜잭션 → 재시도 → 건 단위 fallback. 건 단위 경로는 배치 이전의 검증된 처리(MarketPointRequestService.process)다.
     * 실패할 때마다 DB 상태를 확인하고, DB가 죽어 있으면 남은 시도를 멈추고 이 묶음 첫 건의 레코드부터 장애 대기로 넘긴다.
     */
    private void applyWithFallback(List<Unit> group, Progress progress) {
        List<MarketPointCreditBatchApplier.Item> items = group.stream().map(Unit::item).toList();
        RuntimeException last = null;
        for (int attempt = 1; attempt <= BATCH_ATTEMPTS; attempt++) {
            try {
                progress.results.addAll(batchApplier.applyBatch(items));
                return;
            } catch (RuntimeException e) {
                last = e;
                log.warn("Market point request batch failed. attempt={}/{}, size={}, cause={}",
                        attempt, BATCH_ATTEMPTS, items.size(), e.toString());
                if (!isDatabaseAvailable()) {
                    progress.fail(new Failure(group.get(0).recordIndex(), e, true));
                    return;
                }
                if (attempt < BATCH_ATTEMPTS) {
                    sleep(BATCH_RETRY_BACKOFF_MS[attempt - 1]);
                }
            }
        }

        log.warn("Market point request batch falls back to per-record processing. size={}, lastCause={}", items.size(), last.toString());
        progress.fallback = true;
        for (Unit unit : group) {
            if (!progress.accepts(unit)) {
                continue;
            }
            try {
                progress.results.add(marketPointRequestService.process(unit.item().event(), unit.item().type()));
            } catch (RuntimeException e) {
                // 묶음(v2)은 레코드 하나에 여러 건이라 DLT 레코드만으로는 원인 건을 알 수 없다 (§18.5 F3)
                log.warn("Market point request item failed. recordIndex={}, eventId={}, idempotencyKey={}, cause={}",
                        unit.recordIndex(), unit.item().event().eventId(), unit.item().event().data().idempotencyKey(), e.toString());
                progress.fail(new Failure(unit.recordIndex(), e, !isDatabaseAvailable()));
                if (progress.stopped()) {
                    return;
                }
            }
        }
    }

    /** DB가 살아 있는데 실패했다면 메시지 쪽 문제다. 메시지는 SELECT 1을 실패시킬 수 없다. */
    private boolean isDatabaseAvailable() {
        try {
            consumerDatabase.jdbcTemplate().queryForObject("SELECT 1", Integer.class);
            return true;
        } catch (RuntimeException probeFailure) {
            log.warn("Member-Point DB is unavailable. Market point requests wait for recovery instead of DLT. probe={}",
                    probeFailure.toString());
            return false;
        }
    }

    /** 레코드 1개를 적립할 건 목록으로 바꾼다. v1은 1건, v2 묶음은 items 수만큼. */
    private List<MarketPointRequestEvent> parse(ConsumerRecord<String, String> record, String expectedEventType,
                                                boolean chunkSupported) {
        try {
            JsonNode root = objectMapper.readTree(record.value());
            if (chunkSupported && root.path("schemaVersion").asInt() == CHUNK_SCHEMA_VERSION) {
                MarketPointChunkRequestEvent chunk = objectMapper.treeToValue(root, MarketPointChunkRequestEvent.class);
                validateChunk(chunk, expectedEventType, record.topic());
                return chunk.toItemEvents();
            }
            MarketPointRequestEvent event = objectMapper.treeToValue(root, MarketPointRequestEvent.class);
            validateRequired(event);
            validateType(event, expectedEventType, record.topic());
            return List.of(event);
        } catch (JsonProcessingException | IllegalArgumentException e) {
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

    /** 묶음의 필수 필드는 건마다 확인한다. 한 건이라도 빠지면 발행 측 버그이므로 묶음 전체를 즉시 DLT (§18.5 F1). */
    private void validateChunk(MarketPointChunkRequestEvent chunk, String expectedEventType, String topic) {
        MarketPointChunkRequestEvent.Data data = chunk.data();
        if (chunk.eventId() == null || data == null || data.marketId() == null || data.referenceType() == null
                || data.items() == null || data.items().isEmpty()
                || data.items().stream().anyMatch(item -> item == null || item.predictionId() == null
                || item.memberId() == null || item.amount() == null || item.referenceId() == null
                || item.idempotencyKey() == null)) {
            throw new NonRetryableEventException("Required field is missing. eventId=" + chunk.eventId());
        }
        if (!expectedEventType.equals(chunk.eventType())) {
            throw new NonRetryableEventException("Unexpected eventType=" + chunk.eventType()
                    + " for topic=" + topic + ", eventId=" + chunk.eventId());
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

    /** 적립할 건 1개와 그 건이 온 레코드 위치 */
    private record Unit(int recordIndex, MarketPointCreditBatchApplier.Item item) {
    }

    /**
     * 처리 진행 상태. 원인 건이 나오면 그 레코드의 나머지 건까지만 처리하고 뒤 레코드는 건너뛴다 (다시 전달받는다).
     * DB 장애면 그 자리에서 멈춘다.
     */
    private static final class Progress {

        final List<BatchItemResult> results = new ArrayList<>();
        Failure failure;
        boolean fallback;

        void fail(Failure candidate) {
            failure = Failure.earliest(failure, candidate);
            if (candidate.databaseUnavailable()) {
                failure = candidate;
            }
        }

        boolean stopped() {
            return failure != null && failure.databaseUnavailable();
        }

        boolean accepts(Unit unit) {
            return failure == null || unit.recordIndex() <= failure.index();
        }

        List<Unit> pending(List<Unit> group) {
            return group.stream().filter(this::accepts).toList();
        }

        long count(String status) {
            return results.stream().filter(result -> status.equals(result.getStatus())).count();
        }
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
