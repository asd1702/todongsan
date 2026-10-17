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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 배치 경로는 건 단위 경로와 판정·기록 결과가 같아야 한다 (ASYNC_POINT_FLOW.md §17.5).
 * 같은 이벤트 묶음을 두 경로로 각각 처리하고 (회원·키만 다르고 잔액은 같게), 결과·잔액·이력·결과 이벤트를 비교한다.
 * 배치 경로가 MySQL 전용 문법(VALUES ROW JOIN)을 쓰므로 MySQL 통합 테스트 환경에서 돈다 (§17.9).
 */
@SpringBootTest
@ActiveProfiles("mysqlit")
class MarketPointCreditBatchEquivalenceTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000);

    @Autowired MarketPointRequestService recordPath;
    @Autowired MarketPointCreditBatchApplier batchPath;
    @Autowired MemberRepository memberRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper objectMapper;

    @Test
    void 배치_경로와_건_단위_경로의_결과가_같다() throws Exception {
        Scenario record = new Scenario();
        Scenario batch = new Scenario();

        // 배치 이전에 이미 처리된 키 (재전달)
        recordPath.process(record.alreadyProcessed, PointHistoryType.SETTLE_MARKET);
        recordPath.process(batch.alreadyProcessed, PointHistoryType.SETTLE_MARKET);

        List<BatchItemResult> recordResults = new ArrayList<>();
        for (MarketPointRequestEvent event : record.events) {
            recordResults.add(recordPath.process(event, PointHistoryType.SETTLE_MARKET));
        }
        List<BatchItemResult> batchResults = batchPath.applyBatch(batch.events.stream()
                .map(event -> new MarketPointCreditBatchApplier.Item(event, PointHistoryType.SETTLE_MARKET))
                .toList());

        assertThat(normalize(batchResults)).containsExactlyElementsOf(normalize(recordResults));
        assertThat(batchResults).extracting(BatchItemResult::getStatus).containsExactly(
                "PROCESSED", "PROCESSED", "PROCESSED", "ALREADY_PROCESSED", "FAILED",
                "ALREADY_PROCESSED", "FAILED", "FAILED", "FAILED");

        assertThat(balance(batch.memberA)).isEqualByComparingTo(balance(record.memberA));
        assertThat(balance(batch.memberB)).isEqualByComparingTo(balance(record.memberB));
        assertThat(balance(batch.memberA)).isEqualByComparingTo("1280.00"); // 1000 + 150(이전) + 100 + 30
        assertThat(histories(batch)).containsExactlyElementsOf(histories(record));
        assertThat(outbox(batch)).containsExactlyElementsOf(outbox(record));
    }

    /** 회원 A(잔액 1000), B(잔액 500), 없는 회원 M. 같은 시나리오를 경로마다 새 회원·키로 만든다. */
    private class Scenario {
        final String prefix = "EQ" + SEQ.incrementAndGet();
        final long memberA = newMember("1000.00");
        final long memberB = newMember("500.00");
        final long missingMember = 900_000_000L + SEQ.incrementAndGet();
        final MarketPointRequestEvent alreadyProcessed = event(memberA, "150.00", "MARKET_PREDICTION", "k0");
        final List<MarketPointRequestEvent> events = List.of(
                event(memberA, "100.00", "MARKET_PREDICTION", "k1"),
                event(memberB, "50.555", "MARKET_PREDICTION", "k2"),     // 소수점 셋째 자리 버림
                event(memberA, "30.00", "MARKET_PREDICTION", "k3"),       // 같은 회원 두 번째: 스냅샷 누적
                event(memberA, "100.00", "MARKET_PREDICTION", "k1"),      // 배치 안 중복
                event(memberA, "999.00", "MARKET_PREDICTION", "k1"),      // 같은 키 다른 내용
                alreadyProcessed,                                        // 이전 배치에서 처리됨
                event(missingMember, "10.00", "MARKET_PREDICTION", "k6"),// 회원 없음
                event(memberB, "0", "MARKET_PREDICTION", "k7"),           // 금액 0
                event(memberB, "10.00", "BOGUS", "k8")                    // 잘못된 참조 타입
        );

        MarketPointRequestEvent event(long memberId, String amount, String referenceType, String key) {
            long predictionId = Long.parseLong(key.substring(1)) + 1;
            return new MarketPointRequestEvent(
                    UUID.randomUUID().toString(),
                    "MARKET_SETTLEMENT_PAYOUT_REQUESTED",
                    1,
                    "2026-10-01T10:00:00.000+09:00",
                    "market-service",
                    new MarketPointRequestEvent.Data(42L, predictionId, memberId, new BigDecimal(amount), referenceType,
                            predictionId, "정산 지급", prefix + ":" + key)
            );
        }
    }

    /** 경로마다 다른 회원 ID는 이름(A/B/M)으로 바꿔 비교한다. */
    private static List<String> normalize(List<BatchItemResult> results) {
        return results.stream()
                .map(r -> String.join("|", r.getStatus(), String.valueOf(r.getErrorCode()), String.valueOf(r.getAmount()),
                        String.valueOf(r.getBalanceSnapshot()), String.valueOf(r.getPredictionId())))
                .toList();
    }

    private List<String> histories(Scenario scenario) {
        return jdbcTemplate.queryForList("""
                        SELECT idempotency_key, type, amount, balance_snapshot, status, reference_type, reference_id, reason
                        FROM point_history WHERE idempotency_key LIKE ? ORDER BY idempotency_key
                        """, scenario.prefix + ":%").stream()
                .map(row -> String.join("|",
                        String.valueOf(row.get("IDEMPOTENCY_KEY")).substring(scenario.prefix.length()),
                        String.valueOf(row.get("TYPE")), String.valueOf(row.get("AMOUNT")),
                        String.valueOf(row.get("BALANCE_SNAPSHOT")), String.valueOf(row.get("STATUS")),
                        String.valueOf(row.get("REFERENCE_TYPE")), String.valueOf(row.get("REFERENCE_ID")),
                        String.valueOf(row.get("REASON"))))
                .toList();
    }

    private List<String> outbox(Scenario scenario) throws Exception {
        List<String> rows = new ArrayList<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "SELECT event_type, payload FROM point_outbox_event WHERE payload LIKE ? ORDER BY id",
                "%" + scenario.prefix + ":%")) {
            JsonNode data = objectMapper.readTree((String) row.get("PAYLOAD")).get("data");
            rows.add(String.join("|", String.valueOf(row.get("EVENT_TYPE")),
                    data.get("idempotencyKey").asText().substring(scenario.prefix.length()),
                    data.path("status").asText(), data.path("amount").asText(),
                    data.path("balanceSnapshot").asText(), data.path("errorCode").asText()));
        }
        return rows;
    }

    private long newMember(String balance) {
        long n = SEQ.incrementAndGet();
        Member member = memberRepository.save(Member.builder()
                .nickname("eq-test-" + n)
                .email("eq-test-" + n + "@kakao.com")
                .oauthProvider("KAKAO")
                .oauthId("eq-test-" + n)
                .build());
        jdbcTemplate.update("UPDATE member SET point_balance = ? WHERE id = ?", new BigDecimal(balance), member.getId());
        return member.getId();
    }

    private BigDecimal balance(long memberId) {
        return jdbcTemplate.queryForObject("SELECT point_balance FROM member WHERE id = ?", BigDecimal.class, memberId);
    }
}
