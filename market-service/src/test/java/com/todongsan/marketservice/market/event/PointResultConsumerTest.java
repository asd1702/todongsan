package com.todongsan.marketservice.market.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.todongsan.marketservice.market.service.MarketRefundTransactionService;
import com.todongsan.marketservice.market.service.MarketSettlementTransactionService;
import com.todongsan.marketservice.market.service.PointResultApplyOutcome;
import com.todongsan.marketservice.market.service.PointResultBatchApplier;
import com.todongsan.marketservice.market.service.PointResultBatchApplier.Item;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.listener.BatchListenerFailedException;

/** 배치 결과 반영 흐름 (ASYNC_POINT_FLOW.md §16.1 Q1~Q4). */
@ExtendWith(MockitoExtension.class)
class PointResultConsumerTest {

    private static final String TOPIC = "memberpoint.point-results";

    @Mock
    private PointResultBatchApplier batchApplier;

    @Mock
    private MarketRefundTransactionService refundTransactionService;

    @Mock
    private MarketSettlementTransactionService settlementTransactionService;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private PointResultConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new PointResultConsumer(
                new ObjectMapper(), batchApplier, refundTransactionService, settlementTransactionService, jdbcTemplate);
    }

    @Test
    void appliesValidRecordsAsOneBatchInReceivedOrder() {
        stubBatchApplied();

        consumer.consume(records(
                credited("REFUND", 42, 7),
                failed("REFUND", 42, 8, "MEMBER_NOT_FOUND"),
                credited("SETTLEMENT", 77, 9)
        ));

        List<Item> items = appliedItems();
        assertThat(items).extracting(Item::idempotencyKey).containsExactly(key("REFUND", 42, 7), key("REFUND", 42, 8),
                key("SETTLEMENT", 77, 9));
        assertThat(items).extracting(Item::credited).containsExactly(true, false, true);
        assertThat(items.get(1).errorCode()).isEqualTo("MEMBER_NOT_FOUND");
        assertThat(items.get(2).requestEventType()).isEqualTo("MARKET_SETTLEMENT_PAYOUT_REQUESTED");
    }

    @Test
    void keepsOrderOfSameKeyWithinBatch() {
        stubBatchApplied();

        consumer.consume(records(failed("REFUND", 42, 7, "MEMBER_NOT_FOUND"), credited("REFUND", 42, 7)));

        assertThat(appliedItems()).extracting(Item::credited).containsExactly(false, true);
    }

    @Test
    void malformedRecordIsExcludedAndReportedWithItsIndexAfterCommit() {
        stubBatchApplied();

        assertThatThrownBy(() -> consumer.consume(records(credited("REFUND", 42, 7), "{not-json", credited("REFUND", 42, 8))))
                .isInstanceOfSatisfying(BatchListenerFailedException.class, e -> {
                    assertThat(e.getIndex()).isEqualTo(1);
                    assertThat(e.getCause()).isInstanceOf(NonRetryableEventException.class);
                });

        assertThat(appliedItems()).extracting(Item::idempotencyKey).containsExactly(key("REFUND", 42, 7), key("REFUND", 42, 8));
    }

    @Test
    void detailNotFoundIsReportedAsNonRetryableWithoutRollingBackOthers() {
        when(batchApplier.applyBatch(any())).thenReturn(List.of(
                PointResultApplyOutcome.APPLIED, PointResultApplyOutcome.DETAIL_NOT_FOUND, PointResultApplyOutcome.APPLIED));

        assertThatThrownBy(() -> consumer.consume(records(credited("REFUND", 42, 7), credited("REFUND", 42, 8),
                credited("REFUND", 42, 9))))
                .isInstanceOfSatisfying(BatchListenerFailedException.class, e -> {
                    assertThat(e.getIndex()).isEqualTo(1);
                    assertThat(e.getCause()).isInstanceOf(NonRetryableEventException.class)
                            .hasMessageContaining("Detail not found");
                });
        verify(batchApplier, times(1)).applyBatch(any());
    }

    @Test
    void reportsEarliestFailureIndexWhenSeveralRecordsFail() {
        when(batchApplier.applyBatch(any())).thenReturn(List.of(
                PointResultApplyOutcome.APPLIED, PointResultApplyOutcome.DETAIL_NOT_FOUND, PointResultApplyOutcome.APPLIED));

        assertThatThrownBy(() -> consumer.consume(records(credited("REFUND", 42, 7), credited("REFUND", 42, 8),
                credited("REFUND", 42, 9), "{not-json")))
                .isInstanceOfSatisfying(BatchListenerFailedException.class, e -> assertThat(e.getIndex()).isEqualTo(1));
    }

    @Test
    void unsupportedRequestEventTypeIsNonRetryable() {
        stubBatchApplied();
        String event = credited("REFUND", 42, 8).replace("MARKET_REFUND_REQUESTED", "MARKET_SPEND_REQUESTED");

        assertThatThrownBy(() -> consumer.consume(records(credited("REFUND", 42, 7), event)))
                .isInstanceOfSatisfying(BatchListenerFailedException.class, e -> {
                    assertThat(e.getIndex()).isEqualTo(1);
                    assertThat(e.getCause()).hasMessageContaining("MARKET_SPEND_REQUESTED");
                });
    }

    @Test
    void transientBatchFailureIsRetriedWithoutFallback() {
        when(batchApplier.applyBatch(any()))
                .thenThrow(new IllegalStateException("db blip"))
                .thenReturn(List.of(PointResultApplyOutcome.APPLIED));

        consumer.consume(records(credited("REFUND", 42, 7)));

        verify(batchApplier, times(2)).applyBatch(any());
        verify(refundTransactionService, never()).applyAsyncRefundResult(anyLong(), anyString(), anyBoolean(), any());
    }

    @Test
    void fallsBackToPerRecordWhenBatchKeepsFailing() {
        when(batchApplier.applyBatch(any())).thenThrow(new IllegalStateException("persistent"));
        when(refundTransactionService.applyAsyncRefundResult(anyLong(), anyString(), anyBoolean(), any()))
                .thenReturn(PointResultApplyOutcome.APPLIED);
        when(settlementTransactionService.applyAsyncSettlementResult(anyLong(), anyString(), anyBoolean(), any()))
                .thenReturn(PointResultApplyOutcome.APPLIED);

        consumer.consume(records(credited("REFUND", 42, 7), credited("SETTLEMENT", 77, 9)));

        verify(batchApplier, times(PointResultConsumer.BATCH_ATTEMPTS)).applyBatch(any());
        verify(refundTransactionService).applyAsyncRefundResult(42L, key("REFUND", 42, 7), true, null);
        verify(settlementTransactionService).applyAsyncSettlementResult(77L, key("SETTLEMENT", 77, 9), true, null);
    }

    @Test
    void poisonPillIsIsolatedByPerRecordFallback() {
        IllegalStateException poison = new IllegalStateException("bench poison");
        when(batchApplier.applyBatch(any())).thenThrow(poison);
        when(refundTransactionService.applyAsyncRefundResult(eq(42L), anyString(), anyBoolean(), any()))
                .thenAnswer(invocation -> {
                    if (invocation.getArgument(1).equals(key("REFUND", 42, 8))) {
                        throw poison;
                    }
                    return PointResultApplyOutcome.APPLIED;
                });

        assertThatThrownBy(() -> consumer.consume(records(credited("REFUND", 42, 7), credited("REFUND", 42, 8),
                credited("REFUND", 42, 9))))
                .isInstanceOfSatisfying(PointResultRecordFailedException.class, e -> {
                    assertThat(e.getIndex()).isEqualTo(1);
                    assertThat(e.getCause()).isSameAs(poison);
                });

        verify(refundTransactionService).applyAsyncRefundResult(42L, key("REFUND", 42, 7), true, null);
        // 원인 건 뒤의 건은 반영하지 않고 다시 전달받는다
        verify(refundTransactionService, never()).applyAsyncRefundResult(42L, key("REFUND", 42, 9), true, null);
    }

    @Test
    void databaseOutageStopsRetryingAndWaitsFromFirstRecordInsteadOfFallback() {
        IllegalStateException outage = new IllegalStateException("connection refused");
        when(batchApplier.applyBatch(any())).thenThrow(outage);
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> consumer.consume(records(credited("REFUND", 42, 7), credited("REFUND", 42, 8))))
                .isInstanceOfSatisfying(DatabaseUnavailableException.class, e -> {
                    assertThat(e.getIndex()).isZero();
                    assertThat(e.getCause()).isSameAs(outage);
                });

        // 남은 배치 재시도와 건 단위 fallback을 하지 않는다 (장애 중에는 시도마다 커넥션 대기로 길어진다)
        verify(batchApplier, times(1)).applyBatch(any());
        verify(refundTransactionService, never()).applyAsyncRefundResult(anyLong(), anyString(), anyBoolean(), any());
    }

    @Test
    void databaseOutageDuringPerRecordFallbackWaitsFromThatRecord() {
        IllegalStateException failure = new IllegalStateException("failure");
        when(batchApplier.applyBatch(any())).thenThrow(failure);
        // 배치 시도 3번은 DB가 살아 있고, 건 단위 처리 도중 DB가 죽는다
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class))
                .thenReturn(1, 1, 1)
                .thenThrow(new DataAccessResourceFailureException("db down"));
        when(refundTransactionService.applyAsyncRefundResult(eq(42L), anyString(), anyBoolean(), any()))
                .thenReturn(PointResultApplyOutcome.APPLIED)
                .thenThrow(failure);

        assertThatThrownBy(() -> consumer.consume(records(credited("REFUND", 42, 7), credited("REFUND", 42, 8))))
                .isInstanceOfSatisfying(DatabaseUnavailableException.class, e -> assertThat(e.getIndex()).isEqualTo(1));
    }

    private void stubBatchApplied() {
        when(batchApplier.applyBatch(any())).thenAnswer(invocation -> {
            List<Item> items = invocation.getArgument(0);
            List<PointResultApplyOutcome> outcomes = new ArrayList<>();
            items.forEach(item -> outcomes.add(PointResultApplyOutcome.APPLIED));
            return outcomes;
        });
    }

    @SuppressWarnings("unchecked")
    private List<Item> appliedItems() {
        ArgumentCaptor<List<Item>> captor = ArgumentCaptor.forClass(List.class);
        verify(batchApplier).applyBatch(captor.capture());
        return captor.getValue();
    }

    private static String key(String kind, long marketId, long predictionId) {
        String prefix = "REFUND".equals(kind) ? "MARKET_REFUND" : "MARKET_SETTLEMENT_REWARD";
        return "%s:market:%d:prediction:%d:member:1001".formatted(prefix, marketId, predictionId);
    }

    private static String credited(String kind, long marketId, long predictionId) {
        return event(kind, marketId, predictionId, "POINT_CREDITED",
                "\"status\": \"PROCESSED\", \"balanceSnapshot\": \"1300.00\"");
    }

    private static String failed(String kind, long marketId, long predictionId, String errorCode) {
        return event(kind, marketId, predictionId, "POINT_CREDIT_FAILED", "\"errorCode\": \"" + errorCode + "\"");
    }

    private static String event(String kind, long marketId, long predictionId, String eventType, String extra) {
        String requestEventType = "REFUND".equals(kind) ? "MARKET_REFUND_REQUESTED" : "MARKET_SETTLEMENT_PAYOUT_REQUESTED";
        return """
                {"eventId": "c3e8f0d2-1a4b-4f6e-8d7c-5b9a0e2f3c11", "eventType": "%s", "schemaVersion": 1,
                 "occurredAt": "2026-10-01T18:00:00.456+09:00", "producer": "member-point-service",
                 "data": {"requestEventId": "7f1c2a9e-4b0d-4c5e-9a51-2f7b3c8d1e04", "requestEventType": "%s",
                          "marketId": %d, "predictionId": %d, "memberId": 1001, "idempotencyKey": "%s",
                          "amount": "300.00", %s}}
                """.formatted(eventType, requestEventType, marketId, predictionId, key(kind, marketId, predictionId), extra);
    }

    private static List<ConsumerRecord<String, String>> records(String... values) {
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            records.add(new ConsumerRecord<>(TOPIC, 0, i, "42", values[i]));
        }
        return records;
    }
}
