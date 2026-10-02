package com.todongsan.marketservice.market.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.todongsan.marketservice.market.service.MarketRefundTransactionService;
import com.todongsan.marketservice.market.service.MarketSettlementTransactionService;
import com.todongsan.marketservice.market.service.PointResultApplyOutcome;
import com.todongsan.marketservice.market.service.PointResultBatchApplier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.stereotype.Component;

/**
 * Member-Point 포인트 적립 결과(memberpoint.point-results)를 배치 단위로 환불·정산 detail에 반영한다
 * (ASYNC_POINT_FLOW.md §16.1).
 *
 * ① 파싱·검증 → 처리 불가 건 분리. 묶음 결과(v2, §18)는 건으로 펼친다
 * ② 건을 최대 maxBatchItems건씩 트랜잭션 1개로 반영 (실패 시 배치 재시도, 그래도 실패하면 건 단위 fallback으로 원인 건 격리)
 * ③ 처리 불가 건이나 원인 건이 있으면 가장 앞선 레코드 위치를 BatchListenerFailedException으로 알린다.
 *    error handler가 그 앞 레코드의 offset을 확정하고, 그 레코드를 기존 정책(NonRetryable 즉시, 그 외 재시도 후 DLT)으로 처리한다.
 *    원인 건이 나온 레코드(묶음)의 나머지 건은 끝까지 반영하고, 그 뒤 레코드는 다시 전달받는다 (§18.5 F3).
 *    단, 실패 직후 DB 상태 확인까지 실패하면 DatabaseUnavailableException으로 알려 DLT 대신 복구를 기다린다.
 *    다시 전달된 건은 멱등하게 재반영된다. DB 커밋이 DLT·offset 커밋보다 항상 먼저다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "market.kafka.point-results", name = "enabled", havingValue = "true")
public class PointResultConsumer {

    static final int BATCH_ATTEMPTS = 3;
    private static final long[] BATCH_RETRY_BACKOFF_MS = {200L, 500L};
    private static final int SUPPORTED_SCHEMA_VERSION = 1;
    private static final int CHUNK_SCHEMA_VERSION = 2;
    private static final String POINT_CREDITED = "POINT_CREDITED";
    private static final String POINT_CREDIT_FAILED = "POINT_CREDIT_FAILED";
    private static final String POINT_CREDIT_RESULTS = "POINT_CREDIT_RESULTS";
    private static final String STATUS_FAILED = "FAILED";
    private static final Set<String> CHUNK_ITEM_STATUSES = Set.of("PROCESSED", "ALREADY_PROCESSED", STATUS_FAILED);

    private final ObjectMapper objectMapper;
    private final PointResultBatchApplier batchApplier;
    private final MarketRefundTransactionService refundTransactionService;
    private final MarketSettlementTransactionService settlementTransactionService;
    private final JdbcTemplate jdbcTemplate;
    private final int maxBatchItems;

    public PointResultConsumer(
            ObjectMapper objectMapper,
            PointResultBatchApplier batchApplier,
            MarketRefundTransactionService refundTransactionService,
            MarketSettlementTransactionService settlementTransactionService,
            JdbcTemplate jdbcTemplate,
            @Value("${market.kafka.point-results.max-batch-items:500}") int maxBatchItems
    ) {
        this.objectMapper = objectMapper;
        this.batchApplier = batchApplier;
        this.refundTransactionService = refundTransactionService;
        this.settlementTransactionService = settlementTransactionService;
        this.jdbcTemplate = jdbcTemplate;
        this.maxBatchItems = maxBatchItems;
    }

    @KafkaListener(topics = "${market.kafka.point-results.topic}", batch = "true")
    public void consume(List<ConsumerRecord<String, String>> records) {
        Failure firstFailure = null;
        List<Unit> units = new ArrayList<>();
        for (int i = 0; i < records.size(); i++) {
            try {
                for (PointResultBatchApplier.Item item : parse(records.get(i).value())) {
                    units.add(new Unit(i, item));
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

        for (Applied applied : progress.applied) {
            if (applied.outcome() == PointResultApplyOutcome.DETAIL_NOT_FOUND) {
                PointResultBatchApplier.Item item = applied.unit().item();
                firstFailure = Failure.earliest(firstFailure, new Failure(applied.unit().recordIndex(), new NonRetryableEventException(
                        "Detail not found. requestEventType=" + item.requestEventType()
                                + ", idempotencyKey=" + item.idempotencyKey())));
            }
        }
        if (!units.isEmpty() && !progress.stopped()) {
            log.info("Point result batch applied. records={}, items={}, applied={}, skipped={}, fallback={}",
                    records.size(), units.size(), progress.count(PointResultApplyOutcome.APPLIED),
                    progress.count(PointResultApplyOutcome.SKIPPED), progress.fallback);
        }

        if (firstFailure != null) {
            throw firstFailure.toException();
        }
    }

    /**
     * 배치 트랜잭션 → 재시도 → 건 단위 fallback (Q3).
     * 실패할 때마다 DB 상태를 확인하고, DB가 죽어 있으면 남은 시도를 멈추고 이 묶음 첫 건의 레코드부터 장애 대기로 넘긴다.
     */
    private void applyWithFallback(List<Unit> group, Progress progress) {
        List<PointResultBatchApplier.Item> items = group.stream().map(Unit::item).toList();
        RuntimeException last = null;
        for (int attempt = 1; attempt <= BATCH_ATTEMPTS; attempt++) {
            try {
                List<PointResultApplyOutcome> outcomes = batchApplier.applyBatch(items);
                for (int k = 0; k < group.size(); k++) {
                    progress.applied.add(new Applied(group.get(k), outcomes.get(k)));
                }
                return;
            } catch (RuntimeException e) {
                last = e;
                log.warn("Point result batch failed. attempt={}/{}, size={}, cause={}",
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

        log.warn("Point result batch falls back to per-record processing. size={}, lastCause={}", items.size(), last.toString());
        progress.fallback = true;
        for (Unit unit : group) {
            if (!progress.accepts(unit)) {
                continue;
            }
            try {
                progress.applied.add(new Applied(unit, applySingle(unit.item())));
            } catch (RuntimeException e) {
                // 묶음(v2)은 레코드 하나에 여러 건이라 DLT 레코드만으로는 원인 건을 알 수 없다 (§18.5 F3)
                log.warn("Point result item failed. recordIndex={}, idempotencyKey={}, cause={}",
                        unit.recordIndex(), unit.item().idempotencyKey(), e.toString());
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

    /** 레코드 1개를 반영할 건 목록으로 바꾼다. v1은 1건, v2 묶음 결과는 items 수만큼. */
    private List<PointResultBatchApplier.Item> parse(String value) {
        try {
            JsonNode root = objectMapper.readTree(value);
            if (root.path("schemaVersion").asInt() == CHUNK_SCHEMA_VERSION) {
                return toItems(objectMapper.treeToValue(root, PointResultChunkEvent.class));
            }
            PointResultEvent event = objectMapper.treeToValue(root, PointResultEvent.class);
            validate(event);
            return List.of(toItem(event));
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new NonRetryableEventException("Malformed point result event", e);
        }
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

    /** 묶음 결과는 정산 지급만 있다 (§18.3 C1). 한 건이라도 형식이 틀리면 발행 측 버그이므로 묶음 전체를 즉시 DLT. */
    private List<PointResultBatchApplier.Item> toItems(PointResultChunkEvent event) {
        PointResultChunkEvent.Data data = event.data();
        if (!POINT_CREDIT_RESULTS.equals(event.eventType())) {
            throw new NonRetryableEventException("Unknown eventType=" + event.eventType() + ", eventId=" + event.eventId());
        }
        if (data == null || data.marketId() == null || data.items() == null || data.items().isEmpty()
                || data.items().stream().anyMatch(item -> item == null || item.idempotencyKey() == null
                || !CHUNK_ITEM_STATUSES.contains(item.status()))) {
            throw new NonRetryableEventException("Required field is missing. eventId=" + event.eventId());
        }
        if (!PointResultBatchApplier.MARKET_SETTLEMENT_PAYOUT_REQUESTED.equals(data.requestEventType())) {
            throw new NonRetryableEventException("Unsupported requestEventType=" + data.requestEventType()
                    + ", eventId=" + event.eventId());
        }
        return data.items().stream()
                .map(item -> new PointResultBatchApplier.Item(
                        data.requestEventType(),
                        data.marketId(),
                        item.idempotencyKey(),
                        !STATUS_FAILED.equals(item.status()),
                        item.errorCode()
                ))
                .toList();
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

    /** 반영할 건 1개와 그 건이 온 레코드 위치 */
    private record Unit(int recordIndex, PointResultBatchApplier.Item item) {
    }

    private record Applied(Unit unit, PointResultApplyOutcome outcome) {
    }

    /**
     * 처리 진행 상태. 원인 건이 나오면 그 레코드의 나머지 건까지만 반영하고 뒤 레코드는 건너뛴다 (다시 전달받는다).
     * DB 장애면 그 자리에서 멈춘다.
     */
    private static final class Progress {

        final List<Applied> applied = new ArrayList<>();
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

        long count(PointResultApplyOutcome outcome) {
            return applied.stream().filter(a -> a.outcome() == outcome).count();
        }
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
