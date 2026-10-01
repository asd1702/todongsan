package com.todongsan.marketservice.market.event;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.todongsan.marketservice.market.service.MarketRefundTransactionService;
import com.todongsan.marketservice.market.service.PointResultApplyOutcome;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PointResultConsumerTest {

    private static final String TOPIC = "memberpoint.point-results";
    private static final String KEY = "MARKET_REFUND:market:42:prediction:7:member:1001";

    /** docs/architecture/ASYNC_POINT_FLOW.md §8.4 예시와 동일 (consumer 측 계약 확인) */
    private static final String CREDITED_EVENT = """
            {
              "eventId": "c3e8f0d2-1a4b-4f6e-8d7c-5b9a0e2f3c11",
              "eventType": "POINT_CREDITED",
              "schemaVersion": 1,
              "occurredAt": "2026-09-30T18:00:00.456+09:00",
              "producer": "member-point-service",
              "data": {
                "requestEventId": "7f1c2a9e-4b0d-4c5e-9a51-2f7b3c8d1e04",
                "requestEventType": "MARKET_REFUND_REQUESTED",
                "marketId": 42,
                "predictionId": 7,
                "memberId": 1001,
                "idempotencyKey": "MARKET_REFUND:market:42:prediction:7:member:1001",
                "amount": "300.00",
                "status": "PROCESSED",
                "balanceSnapshot": "1300.00"
              }
            }
            """;

    private static final String FAILED_EVENT = """
            {
              "eventId": "d4f9a1e3-2b5c-4a7f-9e8d-6c0b1f3a4d22",
              "eventType": "POINT_CREDIT_FAILED",
              "schemaVersion": 1,
              "occurredAt": "2026-09-30T18:00:00.456+09:00",
              "producer": "member-point-service",
              "data": {
                "requestEventId": "7f1c2a9e-4b0d-4c5e-9a51-2f7b3c8d1e04",
                "requestEventType": "MARKET_REFUND_REQUESTED",
                "marketId": 42,
                "predictionId": 7,
                "memberId": 1001,
                "idempotencyKey": "MARKET_REFUND:market:42:prediction:7:member:1001",
                "amount": "300.00",
                "errorCode": "MEMBER_NOT_FOUND"
              }
            }
            """;

    @Mock
    private MarketRefundTransactionService refundTransactionService;

    private PointResultConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new PointResultConsumer(new ObjectMapper(), refundTransactionService);
    }

    @Test
    void appliesCreditedResultAsSuccess() {
        when(refundTransactionService.applyAsyncRefundResult(42L, KEY, true, null))
                .thenReturn(PointResultApplyOutcome.APPLIED);

        consumer.consume(record(CREDITED_EVENT));

        verify(refundTransactionService).applyAsyncRefundResult(42L, KEY, true, null);
    }

    @Test
    void appliesFailedResultWithErrorCode() {
        when(refundTransactionService.applyAsyncRefundResult(42L, KEY, false, "MEMBER_NOT_FOUND"))
                .thenReturn(PointResultApplyOutcome.APPLIED);

        consumer.consume(record(FAILED_EVENT));

        verify(refundTransactionService).applyAsyncRefundResult(42L, KEY, false, "MEMBER_NOT_FOUND");
    }

    @Test
    void redeliveredResultIsNotAnError() {
        when(refundTransactionService.applyAsyncRefundResult(42L, KEY, true, null))
                .thenReturn(PointResultApplyOutcome.SKIPPED);

        consumer.consume(record(CREDITED_EVENT));

        verify(refundTransactionService).applyAsyncRefundResult(42L, KEY, true, null);
    }

    @Test
    void missingDetailGoesToDltWithoutRetry() {
        when(refundTransactionService.applyAsyncRefundResult(42L, KEY, true, null))
                .thenReturn(PointResultApplyOutcome.DETAIL_NOT_FOUND);

        assertThatThrownBy(() -> consumer.consume(record(CREDITED_EVENT)))
                .isInstanceOf(NonRetryableEventException.class)
                .hasMessageContaining("Refund detail not found");
    }

    @Test
    void settlementResultIsNotSupportedYet() {
        String event = CREDITED_EVENT.replace("MARKET_REFUND_REQUESTED", "MARKET_SETTLEMENT_PAYOUT_REQUESTED");

        assertThatThrownBy(() -> consumer.consume(record(event)))
                .isInstanceOf(NonRetryableEventException.class)
                .hasMessageContaining("MARKET_SETTLEMENT_PAYOUT_REQUESTED");
        verifyNotApplied();
    }

    @Test
    void unknownEventTypeGoesToDlt() {
        String event = CREDITED_EVENT.replace("\"POINT_CREDITED\"", "\"POINT_DEBITED\"");

        assertThatThrownBy(() -> consumer.consume(record(event)))
                .isInstanceOf(NonRetryableEventException.class);
        verifyNotApplied();
    }

    @Test
    void unsupportedSchemaVersionGoesToDlt() {
        String event = CREDITED_EVENT.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2");

        assertThatThrownBy(() -> consumer.consume(record(event)))
                .isInstanceOf(NonRetryableEventException.class);
        verifyNotApplied();
    }

    @Test
    void malformedJsonGoesToDlt() {
        assertThatThrownBy(() -> consumer.consume(record("{not-json")))
                .isInstanceOf(NonRetryableEventException.class);
        verifyNotApplied();
    }

    @Test
    void missingRequiredFieldGoesToDlt() {
        String event = CREDITED_EVENT.replace("\"marketId\": 42", "\"marketId\": null");

        assertThatThrownBy(() -> consumer.consume(record(event)))
                .isInstanceOf(NonRetryableEventException.class)
                .hasMessageContaining("Required field is missing");
        verifyNotApplied();
    }

    @Test
    void transientFailureIsPropagatedForRetry() {
        when(refundTransactionService.applyAsyncRefundResult(anyLong(), anyString(), anyBoolean(), any()))
                .thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> consumer.consume(record(CREDITED_EVENT)))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(NonRetryableEventException.class);
    }

    private void verifyNotApplied() {
        verify(refundTransactionService, never()).applyAsyncRefundResult(anyLong(), anyString(), anyBoolean(), any());
    }

    private ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>(TOPIC, 0, 0L, "42", value);
    }
}
