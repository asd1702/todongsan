package com.todongsan.marketservice.market.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.todongsan.marketservice.market.type.PredictionStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * [실험 1] 가격 확정 구간 동시성 정합성 검증.
 *
 * 같은 Market 의 여러 선택지에 N 명이 "동시에" 예측 확정을 요청해도,
 *   - Market row + 모든 option row 를 고정 순서로 락 잡는 정책 덕분에
 *   - 가격 합(= 1.0), real pool 합, total_pool, price_history 건수가
 *   전부 정합성을 유지하고 lost update 가 0 건이어야 한다.
 *
 * 실행 전제: Market 전용 MySQL에 market_test 전용 계정/빈 스키마를 준비하고
 * Market Flyway migration을 적용할 수 있어야 한다.
 * MARKET_IT_DB_URL, MARKET_IT_DB_USERNAME, MARKET_IT_DB_PASSWORD를 설정한다.
 * 실행: ./gradlew test --tests "*MarketPredictionConcurrencyConsistencyTest" -Dspring.profiles.active=mysqlit
 */
@SpringBootTest
@ActiveProfiles("mysqlit")
class MarketPredictionConcurrencyConsistencyTest {

    private static final long MARKET_ID = 1L;
    private static final long OPTION_YES = 1L;
    private static final long OPTION_NO = 2L;

    /** 동시 참여자 수. 값을 바꿔 부하를 키워볼 수 있다. */
    private static final int CONCURRENCY = 50;
    private static final BigDecimal BET = new BigDecimal("100.00");
    private static final int OPTION_COUNT = 2;

    @Autowired
    private MarketPredictionTransactionService predictionService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("market_test");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = TRUE", Integer.class))
                .isPositive();
        clearMarketData();
        seedActiveMarketWithTwoOptions();
        seedPendingPredictions(CONCURRENCY);
    }

    @Test
    @DisplayName("동시 N명 예측 확정 - 가격합/풀 정합성 유지, lost update 0건")
    void concurrentConfirmationsKeepPriceAndPoolConsistent() throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch ready = new CountDownLatch(CONCURRENCY);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CONCURRENCY);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();

        for (int i = 1; i <= CONCURRENCY; i++) {
            final long predictionId = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    predictionService.confirmPrediction(predictionId);
                    success.incrementAndGet();
                } catch (Exception e) {
                    failure.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();                       // 모든 스레드 준비 완료까지 대기
        long t0 = System.nanoTime();
        start.countDown();                   // 일제히 출발
        boolean finished = done.await(120, TimeUnit.SECONDS);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        pool.shutdownNow();

        // ---- DB 를 진실의 원천으로 삼아 정합성 측정 ----
        int confirmed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM market_prediction WHERE market_id = ? AND status = ?",
                Integer.class, MARKET_ID, PredictionStatus.CONFIRMED.name());
        BigDecimal priceSum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(current_price),0) FROM market_option WHERE market_id = ?",
                BigDecimal.class, MARKET_ID);
        BigDecimal realPoolSum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(real_pool_amount),0) FROM market_option WHERE market_id = ?",
                BigDecimal.class, MARKET_ID);
        BigDecimal marketTotalPool = jdbc.queryForObject(
                "SELECT total_pool FROM market WHERE id = ?",
                BigDecimal.class, MARKET_ID);
        int historyRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM market_price_history WHERE market_id = ?",
                Integer.class, MARKET_ID);

        BigDecimal expectedPool = BET.multiply(new BigDecimal(CONCURRENCY));

        System.out.println("================ [실험1] 동시성 정합성 결과 ================");
        System.out.println(" 동시 요청 수(CONCURRENCY) : " + CONCURRENCY);
        System.out.println(" 확정 성공(success)        : " + success.get());
        System.out.println(" 확정 실패/충돌(failure)   : " + failure.get());
        System.out.println(" DB CONFIRMED 건수          : " + confirmed + " / " + CONCURRENCY);
        System.out.println(" 선택지 가격 합(=1.0 기대)  : " + priceSum.stripTrailingZeros().toPlainString());
        System.out.println(" real pool 합(기대 " + expectedPool.toPlainString() + ") : " + realPoolSum.toPlainString());
        System.out.println(" market.total_pool          : " + marketTotalPool.toPlainString());
        System.out.println(" price_history 행수(기대 " + (CONCURRENCY * OPTION_COUNT) + ") : " + historyRows);
        System.out.println(" 전체 소요(ms)              : " + elapsedMs);
        System.out.println("==========================================================");

        // ---- 단언 ----
        assertThat(finished).as("120초 내 전체 종료").isTrue();
        assertThat(success.get()).as("모든 확정 성공, lost update 0").isEqualTo(CONCURRENCY);
        assertThat(confirmed).as("DB 상 CONFIRMED 건수 = 동시 요청 수").isEqualTo(CONCURRENCY);
        assertThat(priceSum.subtract(BigDecimal.ONE).abs())
                .as("선택지 가격 합 정합성(≈1.0)")
                .isLessThanOrEqualTo(new BigDecimal("0.00000010"));
        assertThat(realPoolSum).as("real pool 합 정확").isEqualByComparingTo(expectedPool);
        assertThat(marketTotalPool).as("market.total_pool 정확").isEqualByComparingTo(expectedPool);
        assertThat(historyRows).as("price_history 누락/중복 없음").isEqualTo(CONCURRENCY * OPTION_COUNT);
    }

    // ---------------------------------------------------------------- seeding

    private void clearMarketData() {
        jdbc.update("DELETE FROM market_price_history");
        jdbc.update("DELETE FROM market_reputation_update");
        jdbc.update("DELETE FROM market_refund_detail");
        jdbc.update("DELETE FROM market_void");
        jdbc.update("DELETE FROM market_settlement_detail");
        jdbc.update("DELETE FROM market_settlement");
        jdbc.update("DELETE FROM market_prediction");
        jdbc.update("DELETE FROM market_comment");
        jdbc.update("DELETE FROM market_option");
        jdbc.update("DELETE FROM market");
    }

    private void seedActiveMarketWithTwoOptions() {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                INSERT INTO market (
                    id, title, category, answer_type, judge_data_source, judge_criteria, judge_date,
                    status, close_at, total_pool, initial_virtual_liquidity, created_by, created_at, updated_at
                ) VALUES (?, 'Concurrency Market', 'PRICE_INDEX', 'YES_NO', 'TEST', 'TEST',
                          ?, 'ACTIVE', ?, 0.00, 200.00, 1, ?, ?)
                """,
                MARKET_ID, LocalDate.now(), now.plusDays(1), now, now);

        seedOption(OPTION_YES, "YES", "예", 1);
        seedOption(OPTION_NO, "NO", "아니오", 2);
    }

    private void seedOption(long optionId, String code, String text, int order) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                INSERT INTO market_option (
                    id, market_id, option_code, option_text, display_order,
                    virtual_pool_amount, real_pool_amount, total_contract_quantity,
                    current_price, prediction_count, is_result, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 100.00, 0.00, 0.00000000, 0.50000000, 0, FALSE, ?, ?)
                """,
                optionId, MARKET_ID, code, text, order, now, now);
    }

    private void seedPendingPredictions(int count) {
        LocalDateTime now = LocalDateTime.now();
        for (int i = 1; i <= count; i++) {
            long optionId = (i % 2 == 0) ? OPTION_NO : OPTION_YES; // 두 선택지에 번갈아 배치
            jdbc.update("""
                    INSERT INTO market_prediction (
                        id, market_id, option_id, member_id, point_amount,
                        status, point_spend_idempotency_key, attempt_no, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, 'POINT_PENDING', ?, 1, ?, ?)
                    """,
                    (long) i, MARKET_ID, optionId, (long) i, BET,
                    "CONC_TEST_SPEND_KEY_" + i + ":attempt:1", now, now);
        }
    }
}
