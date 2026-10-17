package com.todongsan.memberpointservice.global.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.todongsan.memberpointservice.member.entity.Member;
import com.todongsan.memberpointservice.member.repository.MemberRepository;
import com.todongsan.memberpointservice.point.dto.request.SpendRequest;
import com.todongsan.memberpointservice.point.dto.response.BatchItemResult;
import com.todongsan.memberpointservice.point.entity.PointHistoryType;
import com.todongsan.memberpointservice.point.event.MarketPointCreditBatchApplier;
import com.todongsan.memberpointservice.point.event.MarketPointRequestEvent;
import com.todongsan.memberpointservice.point.service.PointInternalService;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 적립 consumer와 API(spend)는 서로 다른 커넥션 풀을 쓴다 (벌크헤드, ASYNC_POINT_FLOW.md §17.7).
 * 한쪽 풀이 바닥나도 다른 쪽은 처리된다. 풀을 기다리면 1초 안에 실패하도록 connection-timeout을 줄인다.
 * 적립 배치가 MySQL 전용 문법을 쓰므로 MySQL 통합 테스트 환경에서 돈다 (§17.9).
 */
@SpringBootTest(properties = {
        "spring.datasource.hikari.connection-timeout=1000",
        "member-point.kafka.market-point-requests.pool-size=2"
})
@ActiveProfiles("mysqlit")
class ConsumerDatabaseBulkheadTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000);

    @Autowired DataSource apiDataSource;
    @Autowired ConsumerDatabase consumerDatabase;
    @Autowired MarketPointCreditBatchApplier batchApplier;
    @Autowired PointInternalService pointInternalService;
    @Autowired MemberRepository memberRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper objectMapper;

    @Test
    void consumer_풀은_API_풀과_별개이고_크기가_설정을_따른다() {
        HikariDataSource consumer = consumerDatabase.dataSource();
        assertThat(consumer).isNotSameAs(apiDataSource);
        assertThat(consumer.getPoolName()).isEqualTo("market-point-consumer");
        assertThat(consumer.getMaximumPoolSize()).isEqualTo(2);
    }

    @Test
    void API_풀이_바닥나도_적립_배치는_처리된다() throws Exception {
        long memberId = newMember();
        List<Connection> held = exhaust((HikariDataSource) apiDataSource);
        try {
            List<BatchItemResult> results = batchApplier.applyBatch(List.of(
                    new MarketPointCreditBatchApplier.Item(payoutEvent(memberId), PointHistoryType.SETTLE_MARKET)));

            assertThat(results).extracting(BatchItemResult::getStatus).containsExactly("PROCESSED");
        } finally {
            release(held);
        }
    }

    @Test
    void consumer_풀이_바닥나도_spend는_처리된다() throws Exception {
        long memberId = newMember();
        jdbcTemplate.update("UPDATE member SET point_balance = 1000 WHERE id = ?", memberId);
        List<Connection> held = exhaust(consumerDatabase.dataSource());
        try {
            var result = pointInternalService.spend("BULKHEAD:" + UUID.randomUUID(), spendRequest(memberId));

            assertThat(new BigDecimal(result.data().getBalanceSnapshot())).isEqualByComparingTo("700.00");
        } finally {
            release(held);
        }
    }

    private static List<Connection> exhaust(HikariDataSource dataSource) throws Exception {
        List<Connection> held = new ArrayList<>();
        for (int i = 0; i < dataSource.getMaximumPoolSize(); i++) {
            held.add(dataSource.getConnection());
        }
        return held;
    }

    private static void release(List<Connection> held) throws Exception {
        for (Connection connection : held) {
            connection.close();
        }
    }

    private long newMember() {
        long n = SEQ.incrementAndGet();
        Member member = memberRepository.save(Member.builder()
                .nickname("bulkhead-" + n)
                .email("bulkhead-" + n + "@kakao.com")
                .oauthProvider("KAKAO")
                .oauthId("bulkhead-" + n)
                .build());
        return member.getId();
    }

    private static MarketPointRequestEvent payoutEvent(long memberId) {
        long predictionId = SEQ.incrementAndGet();
        return new MarketPointRequestEvent(
                UUID.randomUUID().toString(),
                "MARKET_SETTLEMENT_PAYOUT_REQUESTED",
                1,
                "2026-10-01T10:00:00.000+09:00",
                "market-service",
                new MarketPointRequestEvent.Data(42L, predictionId, memberId, new BigDecimal("100.00"),
                        "MARKET_PREDICTION", predictionId, "정산 지급",
                        "MARKET_SETTLEMENT_REWARD:market:42:prediction:%d:member:%d".formatted(predictionId, memberId))
        );
    }

    private SpendRequest spendRequest(long memberId) {
        return objectMapper.convertValue(Map.of(
                "memberId", memberId,
                "type", "SPEND_MARKET",
                "amount", new BigDecimal("300"),
                "referenceType", "MARKET_PREDICTION",
                "referenceId", 1001L,
                "reason", "Market 예측 참여"
        ), SpendRequest.class);
    }
}
