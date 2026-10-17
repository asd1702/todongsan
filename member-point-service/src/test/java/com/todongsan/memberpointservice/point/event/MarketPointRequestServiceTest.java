package com.todongsan.memberpointservice.point.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.todongsan.memberpointservice.member.entity.Member;
import com.todongsan.memberpointservice.member.repository.MemberRepository;
import com.todongsan.memberpointservice.point.dto.response.BatchItemResult;
import com.todongsan.memberpointservice.point.entity.PointHistoryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 적립과 결과 outbox 기록이 한 트랜잭션인지 확인한다 (JPA + JdbcTemplate, ASYNC_POINT_FLOW.md §13.5 F1).
 * outbox 테이블은 엔티티가 없어 create-drop으로 생기지 않으므로 직접 만든다 (H2 JSON 대신 CLOB).
 */
@SpringBootTest
class MarketPointRequestServiceTest {

    private static final EventContract CONTRACT = EventContract.load("memberpoint.point-results.v1.schema.json");
    private static final String CREATE_OUTBOX = """
            CREATE TABLE IF NOT EXISTS point_outbox_event (
                id BIGINT NOT NULL AUTO_INCREMENT,
                event_id CHAR(36) NOT NULL,
                aggregate_type VARCHAR(50) NOT NULL,
                aggregate_id VARCHAR(50) NOT NULL,
                event_type VARCHAR(80) NOT NULL,
                payload CLOB NOT NULL,
                created_at DATETIME(3) NOT NULL,
                PRIMARY KEY (id),
                UNIQUE (event_id)
            )
            """;
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000);

    @Autowired MarketPointRequestService service;
    @Autowired MemberRepository memberRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(CREATE_OUTBOX);
    }

    @Test
    void 적립과_POINT_CREDITED_결과를_함께_기록한다() throws Exception {
        long memberId = newMember();
        MarketPointRequestEvent event = refundEvent(memberId);

        BatchItemResult result = service.process(event, PointHistoryType.REFUND_MARKET);

        assertThat(result.getStatus()).isEqualTo("PROCESSED");
        assertThat(balance(memberId)).isEqualByComparingTo("300.00");
        assertThat(historyCount(event)).isEqualTo(1);

        Map<String, Object> outbox = outboxRow(event);
        assertThat(outbox.get("AGGREGATE_TYPE")).isEqualTo("point-results");
        assertThat(outbox.get("AGGREGATE_ID")).isEqualTo("42");
        assertThat(outbox.get("EVENT_TYPE")).isEqualTo("POINT_CREDITED");
        String payload = (String) outbox.get("PAYLOAD");
        assertThat(CONTRACT.validate(payload)).isEmpty();
        JsonNode data = objectMapper.readTree(payload).get("data");
        assertThat(data.get("requestEventId").asText()).isEqualTo(event.eventId());
        assertThat(data.get("requestEventType").asText()).isEqualTo("MARKET_REFUND_REQUESTED");
        assertThat(data.get("status").asText()).isEqualTo("PROCESSED");
        assertThat(data.get("amount").asText()).isEqualTo("300.00");
        assertThat(data.get("balanceSnapshot").asText()).isEqualTo("300.00");
        assertThat(data.has("errorCode")).isFalse();
    }

    @Test
    void 같은_요청을_다시_받으면_잔액은_그대로이고_ALREADY_PROCESSED_결과를_다시_기록한다() throws Exception {
        long memberId = newMember();
        MarketPointRequestEvent event = refundEvent(memberId);
        service.process(event, PointHistoryType.REFUND_MARKET);

        BatchItemResult result = service.process(event, PointHistoryType.REFUND_MARKET);

        assertThat(result.getStatus()).isEqualTo("ALREADY_PROCESSED");
        assertThat(balance(memberId)).isEqualByComparingTo("300.00");
        assertThat(historyCount(event)).isEqualTo(1);
        List<String> payloads = outboxPayloads(event);
        assertThat(payloads).hasSize(2);
        assertThat(CONTRACT.validate(payloads.get(1))).isEmpty();
        assertThat(objectMapper.readTree(payloads.get(1)).get("data").get("status").asText())
                .isEqualTo("ALREADY_PROCESSED");
    }

    @Test
    void 영구_실패는_POINT_CREDIT_FAILED_결과로_기록한다() throws Exception {
        long missingMemberId = 900_000_000L + SEQ.incrementAndGet();
        MarketPointRequestEvent event = refundEvent(missingMemberId);

        BatchItemResult result = service.process(event, PointHistoryType.REFUND_MARKET);

        assertThat(result.getStatus()).isEqualTo("FAILED");
        assertThat(historyCount(event)).isZero();
        Map<String, Object> outbox = outboxRow(event);
        assertThat(outbox.get("EVENT_TYPE")).isEqualTo("POINT_CREDIT_FAILED");
        String payload = (String) outbox.get("PAYLOAD");
        assertThat(CONTRACT.validate(payload)).isEmpty();
        JsonNode data = objectMapper.readTree(payload).get("data");
        assertThat(data.get("errorCode").asText()).isEqualTo("MEMBER_NOT_FOUND");
        assertThat(data.has("status")).isFalse();
        assertThat(data.has("balanceSnapshot")).isFalse();
    }

    @Test
    void outbox_기록이_실패하면_적립도_롤백된다() {
        long memberId = newMember();
        MarketPointRequestEvent event = refundEvent(memberId);
        jdbcTemplate.execute("DROP TABLE point_outbox_event");
        try {
            assertThatThrownBy(() -> service.process(event, PointHistoryType.REFUND_MARKET))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            jdbcTemplate.execute(CREATE_OUTBOX);
        }

        assertThat(balance(memberId)).isEqualByComparingTo("0.00");
        assertThat(historyCount(event)).isZero();
    }

    private long newMember() {
        long n = SEQ.incrementAndGet();
        Member member = memberRepository.save(Member.builder()
                .nickname("tx-test-" + n)
                .email("tx-test-" + n + "@kakao.com")
                .oauthProvider("KAKAO")
                .oauthId("tx-test-" + n)
                .build());
        return member.getId();
    }

    private MarketPointRequestEvent refundEvent(long memberId) {
        long predictionId = SEQ.incrementAndGet();
        return new MarketPointRequestEvent(
                UUID.randomUUID().toString(),
                "MARKET_REFUND_REQUESTED",
                1,
                "2026-10-01T10:00:00.000+09:00",
                "market-service",
                new MarketPointRequestEvent.Data(
                        42L,
                        predictionId,
                        memberId,
                        new BigDecimal("300.00"),
                        "MARKET_PREDICTION",
                        predictionId,
                        "Market 무효 처리 환불",
                        "MARKET_REFUND:market:42:prediction:%d:member:%d".formatted(predictionId, memberId)
                )
        );
    }

    private BigDecimal balance(long memberId) {
        return jdbcTemplate.queryForObject("SELECT point_balance FROM member WHERE id = ?", BigDecimal.class, memberId);
    }

    private int historyCount(MarketPointRequestEvent event) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM point_history WHERE idempotency_key = ?",
                Integer.class, event.data().idempotencyKey());
    }

    private Map<String, Object> outboxRow(MarketPointRequestEvent event) {
        return jdbcTemplate.queryForMap("SELECT * FROM point_outbox_event WHERE payload LIKE ?",
                "%" + event.eventId() + "%");
    }

    private List<String> outboxPayloads(MarketPointRequestEvent event) {
        return jdbcTemplate.queryForList("SELECT payload FROM point_outbox_event WHERE payload LIKE ? ORDER BY id",
                String.class, "%" + event.eventId() + "%");
    }
}
