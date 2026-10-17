package com.todongsan.memberpointservice.point.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.todongsan.memberpointservice.member.entity.Member;
import com.todongsan.memberpointservice.member.repository.MemberRepository;
import com.todongsan.memberpointservice.point.dto.response.BatchItemResult;
import com.todongsan.memberpointservice.point.entity.PointHistoryType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 정산 지급 묶음(v2)은 건별 요청(v1)과 판정·기록 결과가 같아야 한다 (ASYNC_POINT_FLOW.md §18.6).
 * 결과 이벤트만 형식이 다르다: v1은 건마다 1행, v2는 요청 묶음·트랜잭션마다 1행에 건별 결과.
 */
@SpringBootTest
@ActiveProfiles("mysqlit")
class MarketPointChunkEquivalenceTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000);
    private static final EventContract REQUEST_V2 = EventContract.load("market.settlement-payout-requests.v2.schema.json");
    private static final EventContract RESULT_V2 = EventContract.load("memberpoint.point-results.v2.schema.json");

    @Autowired MarketPointCreditBatchApplier batchPath;
    @Autowired MemberRepository memberRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper objectMapper;

    @Test
    void 묶음_요청과_건별_요청의_적립_결과가_같다() throws Exception {
        Scenario v1 = new Scenario();
        Scenario v2 = new Scenario();
        batchPath.applyBatch(List.of(item(v1.single(v1.memberA, "150.00", "k0"))));
        batchPath.applyBatch(List.of(item(v2.single(v2.memberA, "150.00", "k0"))));

        List<BatchItemResult> v1Results = batchPath.applyBatch(v1.specs().stream()
                .map(spec -> item(v1.single(spec.memberId(), spec.amount(), spec.key()))).toList());
        List<BatchItemResult> v2Results = batchPath.applyBatch(v2.chunk(v2.specs()).toItemEvents().stream()
                .map(MarketPointChunkEquivalenceTest::item).toList());

        assertThat(normalize(v2Results)).containsExactlyElementsOf(normalize(v1Results));
        assertThat(v2Results).extracting(BatchItemResult::getStatus).containsExactly(
                "PROCESSED", "PROCESSED", "PROCESSED", "ALREADY_PROCESSED", "FAILED", "ALREADY_PROCESSED", "FAILED", "FAILED");
        assertThat(balance(v2.memberA)).isEqualByComparingTo(balance(v1.memberA)).isEqualByComparingTo("1280.00");
        assertThat(balance(v2.memberB)).isEqualByComparingTo(balance(v1.memberB));
        assertThat(histories(v2)).containsExactlyElementsOf(histories(v1));

        // 결과: v1은 건마다 1행(이전 k0 포함 9행), v2는 이전 k0 1행(건별) + 묶음 1행
        List<String> v1Items = new ArrayList<>();
        for (JsonNode payload : resultPayloads(v1)) {
            JsonNode data = payload.get("data");
            v1Items.add(itemLine(data, v1.prefix, "POINT_CREDIT_FAILED".equals(payload.get("eventType").asText())
                    ? "FAILED" : data.get("status").asText()));
        }
        List<JsonNode> v2Payloads = resultPayloads(v2);
        assertThat(v2Payloads).hasSize(2);
        JsonNode chunkResult = v2Payloads.get(1);
        assertThat(chunkResult.get("eventType").asText()).isEqualTo("POINT_CREDIT_RESULTS");
        assertThat(chunkResult.get("schemaVersion").asInt()).isEqualTo(2);
        List<String> v2Items = new ArrayList<>();
        v2Items.add(itemLine(v2Payloads.get(0).get("data"), v2.prefix, "PROCESSED"));
        chunkResult.get("data").get("items").forEach(node -> v2Items.add(itemLine(node, v2.prefix, node.get("status").asText())));
        assertThat(v2Items).containsExactlyElementsOf(v1Items);
    }

    @Test
    void 같은_건이_건별과_묶음으로_모두_와도_한_번만_적립한다() {
        Scenario s = new Scenario();
        Spec spec = new Spec(s.memberA, "300.00", "k1");

        BatchItemResult first = batchPath.applyBatch(List.of(item(s.single(spec.memberId(), spec.amount(), spec.key())))).get(0);
        BatchItemResult second = batchPath.applyBatch(s.chunk(List.of(spec)).toItemEvents().stream()
                .map(MarketPointChunkEquivalenceTest::item).toList()).get(0);
        assertThat(first.getStatus()).isEqualTo("PROCESSED");
        assertThat(second.getStatus()).isEqualTo("ALREADY_PROCESSED");

        Spec reverse = new Spec(s.memberB, "70.00", "k2");
        BatchItemResult third = batchPath.applyBatch(s.chunk(List.of(reverse)).toItemEvents().stream()
                .map(MarketPointChunkEquivalenceTest::item).toList()).get(0);
        BatchItemResult fourth = batchPath.applyBatch(List.of(item(s.single(reverse.memberId(), reverse.amount(), reverse.key())))).get(0);
        assertThat(third.getStatus()).isEqualTo("PROCESSED");
        assertThat(fourth.getStatus()).isEqualTo("ALREADY_PROCESSED");

        assertThat(balance(s.memberA)).isEqualByComparingTo("1300.00");
        assertThat(balance(s.memberB)).isEqualByComparingTo("570.00");
        assertThat(histories(s)).hasSize(2);
    }

    @Test
    void 묶음을_나눠_처리하면_트랜잭션마다_결과_1행이고_계약을_지킨다() throws Exception {
        Scenario s = new Scenario();
        // 발행 측(Market)이 보내는 모양 그대로: 금액은 문자열 (§8.1 E6)
        String json = """
                {
                  "eventId": "%s", "eventType": "MARKET_SETTLEMENT_PAYOUT_REQUESTED", "schemaVersion": 2,
                  "occurredAt": "2026-10-02T10:00:00.000+09:00", "producer": "market-service",
                  "data": {
                    "marketId": 42, "chunkNo": 0, "referenceType": "MARKET_PREDICTION", "reason": "정산 지급",
                    "items": [
                      { "predictionId": 2, "memberId": %d, "amount": "100.00", "referenceId": 2, "idempotencyKey": "%s" },
                      { "predictionId": 3, "memberId": %d, "amount": "10.00", "referenceId": 3, "idempotencyKey": "%s" },
                      { "predictionId": 4, "memberId": %d, "amount": "20.00", "referenceId": 4, "idempotencyKey": "%s" }
                    ]
                  }
                }
                """.formatted(UUID.randomUUID(), s.memberA, s.key("k1"), s.missingMember, s.key("k2"), s.memberB, s.key("k3"));
        assertThat(REQUEST_V2.validate(json)).isEmpty();
        MarketPointChunkRequestEvent chunk = objectMapper.readValue(json, MarketPointChunkRequestEvent.class);

        List<MarketPointCreditBatchApplier.Item> items = chunk.toItemEvents().stream().map(MarketPointChunkEquivalenceTest::item).toList();
        batchPath.applyBatch(items.subList(0, 2));
        batchPath.applyBatch(items.subList(2, 3));

        List<JsonNode> payloads = resultPayloads(s);
        assertThat(payloads).hasSize(2);
        for (JsonNode payload : payloads) {
            assertThat(RESULT_V2.validate(objectMapper.writeValueAsString(payload))).isEmpty();
            assertThat(payload.get("data").get("requestEventId").asText()).isEqualTo(chunk.eventId());
        }
        assertThat(payloads.get(0).get("data").get("items")).extracting(node -> node.get("status").asText())
                .containsExactly("PROCESSED", "FAILED");
        assertThat(payloads.get(0).get("data").get("items").get(1).get("errorCode").asText()).isEqualTo("MEMBER_NOT_FOUND");
        assertThat(payloads.get(1).get("data").get("items")).extracting(node -> node.get("status").asText())
                .containsExactly("PROCESSED");
    }

    private record Spec(long memberId, String amount, String key) {
    }

    /** 회원 A(잔액 1000), B(잔액 500), 없는 회원 M. 경로마다 새 회원·키로 만든다. */
    private class Scenario {
        final String prefix = "CH" + SEQ.incrementAndGet();
        final long memberA = newMember("1000.00");
        final long memberB = newMember("500.00");
        final long missingMember = 900_000_000L + SEQ.incrementAndGet();

        /** 기존 동등성 테스트(MarketPointCreditBatchEquivalenceTest)와 같은 경우. 참조 타입은 묶음 공통이라 잘못된 참조 타입 경우는 없다 */
        List<Spec> specs() {
            return List.of(
                    new Spec(memberA, "100.00", "k1"),
                    new Spec(memberB, "50.555", "k2"),       // 소수점 셋째 자리 버림
                    new Spec(memberA, "30.00", "k3"),        // 같은 회원 두 번째: 스냅샷 누적
                    new Spec(memberA, "100.00", "k1"),       // 묶음 안 중복
                    new Spec(memberA, "999.00", "k1"),       // 같은 키 다른 내용
                    new Spec(memberA, "150.00", "k0"),       // 이전에 처리됨
                    new Spec(missingMember, "10.00", "k6"),  // 회원 없음
                    new Spec(memberB, "0", "k7"));           // 금액 0
        }

        MarketPointRequestEvent single(long memberId, String amount, String key) {
            long predictionId = predictionId(key);
            return new MarketPointRequestEvent(UUID.randomUUID().toString(), "MARKET_SETTLEMENT_PAYOUT_REQUESTED", 1,
                    "2026-10-02T10:00:00.000+09:00", "market-service",
                    new MarketPointRequestEvent.Data(42L, predictionId, memberId, new BigDecimal(amount), "MARKET_PREDICTION",
                            predictionId, "정산 지급", key(key)));
        }

        MarketPointChunkRequestEvent chunk(List<Spec> specs) {
            return new MarketPointChunkRequestEvent(UUID.randomUUID().toString(), "MARKET_SETTLEMENT_PAYOUT_REQUESTED", 2,
                    "2026-10-02T10:00:00.000+09:00", "market-service",
                    new MarketPointChunkRequestEvent.Data(42L, 0, "MARKET_PREDICTION", "정산 지급", specs.stream()
                            .map(spec -> new MarketPointChunkRequestEvent.Item(predictionId(spec.key()), spec.memberId(),
                                    new BigDecimal(spec.amount()), predictionId(spec.key()), key(spec.key())))
                            .toList()));
        }

        /** 계약의 키 형식을 지키면서 실행마다 다른 키를 만든다 */
        String key(String key) {
            return "MARKET_SETTLEMENT_REWARD:market:" + prefix.substring(2) + ":prediction:" + predictionId(key) + ":member:" + key.charAt(1);
        }

        String keyPrefix() {
            return "MARKET_SETTLEMENT_REWARD:market:" + prefix.substring(2) + ":";
        }

        private long predictionId(String key) {
            return Long.parseLong(key.substring(1)) + 1;
        }
    }

    private static MarketPointCreditBatchApplier.Item item(MarketPointRequestEvent event) {
        return new MarketPointCreditBatchApplier.Item(event, PointHistoryType.SETTLE_MARKET);
    }

    private static List<String> normalize(List<BatchItemResult> results) {
        return results.stream()
                .map(r -> String.join("|", r.getStatus(), String.valueOf(r.getErrorCode()), String.valueOf(r.getAmount()),
                        String.valueOf(r.getBalanceSnapshot()), String.valueOf(r.getPredictionId())))
                .toList();
    }

    private static String itemLine(JsonNode data, String prefix, String status) {
        String key = data.get("idempotencyKey").asText();
        return String.join("|", key.substring(key.indexOf(":prediction:")), status, data.path("amount").asText(),
                data.path("balanceSnapshot").asText(), data.path("errorCode").asText());
    }

    private List<String> histories(Scenario scenario) {
        return jdbcTemplate.queryForList("""
                        SELECT idempotency_key, type, amount, balance_snapshot, status, reference_type, reference_id, reason, request_hash
                        FROM point_history WHERE idempotency_key LIKE ? ORDER BY idempotency_key
                        """, scenario.keyPrefix() + "%").stream()
                .map(row -> String.join("|",
                        String.valueOf(row.get("IDEMPOTENCY_KEY")).substring(scenario.keyPrefix().length()),
                        String.valueOf(row.get("TYPE")), String.valueOf(row.get("AMOUNT")),
                        String.valueOf(row.get("BALANCE_SNAPSHOT")), String.valueOf(row.get("STATUS")),
                        String.valueOf(row.get("REFERENCE_TYPE")), String.valueOf(row.get("REFERENCE_ID")),
                        String.valueOf(row.get("REASON"))))
                .toList();
    }

    private List<JsonNode> resultPayloads(Scenario scenario) throws Exception {
        List<JsonNode> payloads = new ArrayList<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "SELECT payload FROM point_outbox_event WHERE payload LIKE ? ORDER BY id", "%" + scenario.keyPrefix() + "%")) {
            payloads.add(objectMapper.readTree((String) row.get("PAYLOAD")));
        }
        return payloads;
    }

    private long newMember(String balance) {
        long n = SEQ.incrementAndGet();
        Member member = memberRepository.save(Member.builder()
                .nickname("ch-test-" + n)
                .email("ch-test-" + n + "@kakao.com")
                .oauthProvider("KAKAO")
                .oauthId("ch-test-" + n)
                .build());
        jdbcTemplate.update("UPDATE member SET point_balance = ? WHERE id = ?", new BigDecimal(balance), member.getId());
        return member.getId();
    }

    private BigDecimal balance(long memberId) {
        return jdbcTemplate.queryForObject("SELECT point_balance FROM member WHERE id = ?", BigDecimal.class, memberId);
    }
}
