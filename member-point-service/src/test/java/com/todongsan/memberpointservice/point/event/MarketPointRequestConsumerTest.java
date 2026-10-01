package com.todongsan.memberpointservice.point.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.todongsan.memberpointservice.point.dto.response.BatchItemResult;
import com.todongsan.memberpointservice.point.entity.PointHistoryType;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketPointRequestConsumerTest {

    private static final String REFUND_TOPIC = "market.refund-requests";
    private static final String PAYOUT_TOPIC = "market.settlement-payout-requests";

    /** docs/architecture/ASYNC_POINT_FLOW.md §8.3 예시와 동일 (consumer 측 계약 확인) */
    static final String REFUND_EVENT = """
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
    MarketPointRequestService marketPointRequestService;

    @Mock
    JdbcTemplate jdbcTemplate;

    MarketPointRequestConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new MarketPointRequestConsumer(new ObjectMapper(), marketPointRequestService, jdbcTemplate);
    }

    @Test
    void 환불_요청_이벤트를_REFUND_MARKET_적립으로_처리한다() throws Exception {
        stubProcessed();

        consumer.consumeRefund(record(REFUND_EVENT));

        ArgumentCaptor<MarketPointRequestEvent> captor = ArgumentCaptor.forClass(MarketPointRequestEvent.class);
        verify(marketPointRequestService).process(captor.capture(), eq(PointHistoryType.REFUND_MARKET));
        MarketPointRequestEvent.Data data = captor.getValue().data();
        assertThat(captor.getValue().eventId()).isEqualTo("7f1c2a9e-4b0d-4c5e-9a51-2f7b3c8d1e04");
        assertThat(data.marketId()).isEqualTo(42L);
        assertThat(data.predictionId()).isEqualTo(7L);
        assertThat(data.memberId()).isEqualTo(1001L);
        assertThat(data.amount()).isEqualByComparingTo(new BigDecimal("300.00"));
        assertThat(data.referenceType()).isEqualTo("MARKET_PREDICTION");
        assertThat(data.referenceId()).isEqualTo(7L);
        assertThat(data.reason()).isEqualTo("Market 무효 처리 환불");
        assertThat(data.idempotencyKey()).isEqualTo("MARKET_REFUND:market:42:prediction:7:member:1001");
    }

    @Test
    void 정산_지급_요청_이벤트는_SETTLE_MARKET_적립으로_처리한다() throws Exception {
        stubProcessed();

        consumer.consumeSettlementPayout(record(PAYOUT_TOPIC, payoutEvent()));

        verify(marketPointRequestService).process(any(), eq(PointHistoryType.SETTLE_MARKET));
    }

    @Test
    void 토픽과_다른_업무의_이벤트는_재시도하지_않는다() {
        assertThatThrownBy(() -> consumer.consumeRefund(record(REFUND_TOPIC, payoutEvent())))
                .isInstanceOf(NonRetryableEventException.class)
                .hasMessageContaining("MARKET_SETTLEMENT_PAYOUT_REQUESTED")
                .hasMessageContaining(REFUND_TOPIC);
        assertThatThrownBy(() -> consumer.consumeSettlementPayout(record(PAYOUT_TOPIC, REFUND_EVENT)))
                .isInstanceOf(NonRetryableEventException.class)
                .hasMessageContaining("MARKET_REFUND_REQUESTED")
                .hasMessageContaining(PAYOUT_TOPIC);
        verifyNotProcessed();
    }

    private static String payoutEvent() {
        return REFUND_EVENT
                .replace("MARKET_REFUND_REQUESTED", "MARKET_SETTLEMENT_PAYOUT_REQUESTED")
                .replace("MARKET_REFUND:", "MARKET_SETTLEMENT_REWARD:");
    }

    @Test
    void 모르는_선택_필드는_무시한다() throws Exception {
        stubProcessed();
        String event = REFUND_EVENT.replace("\"producer\": \"market-service\",",
                "\"producer\": \"market-service\", \"traceId\": \"abc\",");

        consumer.consumeRefund(record(event));

        verify(marketPointRequestService).process(any(), eq(PointHistoryType.REFUND_MARKET));
    }

    @Test
    void 모르는_eventType은_재시도하지_않는다() {
        String event = REFUND_EVENT.replace("MARKET_REFUND_REQUESTED", "MARKET_SPEND_REQUESTED");

        assertThatThrownBy(() -> consumer.consumeRefund(record(event)))
                .isInstanceOf(NonRetryableEventException.class)
                .hasMessageContaining("MARKET_SPEND_REQUESTED");
        verifyNotProcessed();
    }

    @Test
    void 지원하지_않는_schemaVersion은_재시도하지_않는다() {
        String event = REFUND_EVENT.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2");

        assertThatThrownBy(() -> consumer.consumeRefund(record(event)))
                .isInstanceOf(NonRetryableEventException.class)
                .hasMessageContaining("schemaVersion=2");
        verifyNotProcessed();
    }

    @Test
    void 깨진_JSON은_재시도하지_않는다() {
        assertThatThrownBy(() -> consumer.consumeRefund(record("{not-json")))
                .isInstanceOf(NonRetryableEventException.class);
        verifyNotProcessed();
    }

    @Test
    void data가_없으면_재시도하지_않는다() {
        String event = """
                {"eventId":"7f1c2a9e-4b0d-4c5e-9a51-2f7b3c8d1e04","eventType":"MARKET_REFUND_REQUESTED","schemaVersion":1}
                """;

        assertThatThrownBy(() -> consumer.consumeRefund(record(event)))
                .isInstanceOf(NonRetryableEventException.class);
        verifyNotProcessed();
    }

    @ParameterizedTest
    @ValueSource(strings = {"marketId", "predictionId", "memberId", "amount", "referenceType", "referenceId", "idempotencyKey"})
    void 필수_필드가_없으면_재시도하지_않는다(String field) {
        String event = REFUND_EVENT.replaceAll("(\"" + field + "\": )[^,\\n]+", "$1null");

        assertThatThrownBy(() -> consumer.consumeRefund(record(event)))
                .isInstanceOf(NonRetryableEventException.class)
                .hasMessageContaining("Required field is missing");
        verifyNotProcessed();
    }

    @Test
    void DB가_살아있는데_처리에_실패하면_그대로_전파해_기본_재시도_후_DLT로_보낸다() {
        when(marketPointRequestService.process(any(), any())).thenThrow(new IllegalStateException("bad message"));

        assertThatThrownBy(() -> consumer.consumeRefund(record(REFUND_EVENT)))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(NonRetryableEventException.class);
    }

    @Test
    void 처리_실패_후_DB_상태_확인도_실패하면_DB_장애로_알려_복구를_기다리게_한다() {
        IllegalStateException failure = new IllegalStateException("connection refused");
        when(marketPointRequestService.process(any(), any())).thenThrow(failure);
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> consumer.consumeRefund(record(REFUND_EVENT)))
                .isInstanceOf(DatabaseUnavailableException.class)
                .hasCause(failure);
    }

    private void stubProcessed() {
        when(marketPointRequestService.process(any(), any()))
                .thenReturn(BatchItemResult.builder().status("PROCESSED").build());
    }

    private void verifyNotProcessed() {
        verify(marketPointRequestService, never()).process(any(), any());
    }

    private ConsumerRecord<String, String> record(String value) {
        return record(REFUND_TOPIC, value);
    }

    private ConsumerRecord<String, String> record(String topic, String value) {
        return new ConsumerRecord<>(topic, 0, 0L, "1001", value);
    }
}
