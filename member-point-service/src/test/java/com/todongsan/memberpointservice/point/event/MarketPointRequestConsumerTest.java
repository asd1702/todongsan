package com.todongsan.memberpointservice.point.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.todongsan.memberpointservice.point.dto.response.BatchItemResult;
import com.todongsan.memberpointservice.point.entity.PointHistoryType;
import com.todongsan.memberpointservice.point.service.PointInternalService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketPointRequestConsumerTest {

    private static final String TOPIC = "market.point-requests";

    /** docs/architecture/ASYNC_POINT_FLOW.md §8.3 예시와 동일 (consumer 측 계약 확인) */
    private static final String REFUND_EVENT = """
            {
              "eventId": "7f1c2a9e-4b0d-4c5e-9a51-2f7b3c8d1e04",
              "eventType": "MARKET_REFUND_REQUESTED",
              "schemaVersion": 1,
              "occurredAt": "2026-09-30T18:00:00.123+09:00",
              "producer": "market-service",
              "data": {
                "marketId": 42,
                "predictionId": 7,
                "memberId": 1001,
                "amount": "300.00",
                "referenceType": "MARKET_PREDICTION",
                "referenceId": 7,
                "reason": "Market 무효 처리 환불",
                "idempotencyKey": "MARKET_REFUND:market:42:prediction:7:member:1001"
              }
            }
            """;

    @Mock
    PointInternalService pointInternalService;

    MarketPointRequestConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new MarketPointRequestConsumer(new ObjectMapper(), pointInternalService);
    }

    @Test
    void 환불_요청_이벤트를_REFUND_MARKET_적립으로_처리한다() {
        stubProcessed();

        consumer.consume(record(REFUND_EVENT));

        verify(pointInternalService).creditMarketPoint(
                PointHistoryType.REFUND_MARKET,
                7L,
                1001L,
                new BigDecimal("300.00"),
                "MARKET_PREDICTION",
                7L,
                "Market 무효 처리 환불",
                "MARKET_REFUND:market:42:prediction:7:member:1001"
        );
    }

    @Test
    void 정산_지급_요청_이벤트는_SETTLE_MARKET_적립으로_처리한다() {
        stubProcessed();
        String event = REFUND_EVENT
                .replace("MARKET_REFUND_REQUESTED", "MARKET_SETTLEMENT_PAYOUT_REQUESTED")
                .replace("MARKET_REFUND:", "MARKET_SETTLEMENT_REWARD:");

        consumer.consume(record(event));

        verify(pointInternalService).creditMarketPoint(
                eq(PointHistoryType.SETTLE_MARKET), anyLong(), anyLong(), any(), anyString(), anyLong(), anyString(),
                eq("MARKET_SETTLEMENT_REWARD:market:42:prediction:7:member:1001"));
    }

    @Test
    void 모르는_선택_필드는_무시한다() {
        stubProcessed();
        String event = REFUND_EVENT.replace("\"producer\": \"market-service\",",
                "\"producer\": \"market-service\", \"traceId\": \"abc\",");

        consumer.consume(record(event));

        verify(pointInternalService).creditMarketPoint(
                eq(PointHistoryType.REFUND_MARKET), anyLong(), anyLong(), any(), anyString(), anyLong(), anyString(), anyString());
    }

    @Test
    void 모르는_eventType은_재시도하지_않는다() {
        String event = REFUND_EVENT.replace("MARKET_REFUND_REQUESTED", "MARKET_SPEND_REQUESTED");

        assertThatThrownBy(() -> consumer.consume(record(event)))
                .isInstanceOf(NonRetryableEventException.class)
                .hasMessageContaining("MARKET_SPEND_REQUESTED");
        verifyNotCredited();
    }

    @Test
    void 지원하지_않는_schemaVersion은_재시도하지_않는다() {
        String event = REFUND_EVENT.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2");

        assertThatThrownBy(() -> consumer.consume(record(event)))
                .isInstanceOf(NonRetryableEventException.class)
                .hasMessageContaining("schemaVersion=2");
        verifyNotCredited();
    }

    @Test
    void 깨진_JSON은_재시도하지_않는다() {
        assertThatThrownBy(() -> consumer.consume(record("{not-json")))
                .isInstanceOf(NonRetryableEventException.class);
        verifyNotCredited();
    }

    @Test
    void data가_없으면_재시도하지_않는다() {
        String event = """
                {"eventId":"7f1c2a9e-4b0d-4c5e-9a51-2f7b3c8d1e04","eventType":"MARKET_REFUND_REQUESTED","schemaVersion":1}
                """;

        assertThatThrownBy(() -> consumer.consume(record(event)))
                .isInstanceOf(NonRetryableEventException.class);
        verifyNotCredited();
    }

    @Test
    void 처리_중_일시_장애는_그대로_전파해_재시도하게_한다() {
        when(pointInternalService.creditMarketPoint(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> consumer.consume(record(REFUND_EVENT)))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(NonRetryableEventException.class);
    }

    private void stubProcessed() {
        when(pointInternalService.creditMarketPoint(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(BatchItemResult.builder().status("PROCESSED").build());
    }

    private void verifyNotCredited() {
        verify(pointInternalService, never())
                .creditMarketPoint(any(), any(), any(), any(), any(), any(), any(), any());
    }

    private ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>(TOPIC, 0, 0L, "1001", value);
    }
}
