package com.todongsan.marketservice.market.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.todongsan.marketservice.market.repository.MarketMapper;
import com.todongsan.marketservice.market.type.PredictionStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("mysqlit")
class MarketTransactionBoundaryExperimentTest {

    private static final long MARKET_A = 2300L;
    private static final long MARKET_B = 2400L;
    private static final long OPTION_A_YES = 2301L;
    private static final long OPTION_A_NO = 2302L;
    private static final long OPTION_B_YES = 2401L;
    private static final long OPTION_B_NO = 2402L;
    private static final long PREDICTION_A = 5001L;
    private static final long PREDICTION_B = 5002L;
    private static final long EXTERNAL_DELAY_MS = 2_000L;

    @Autowired
    private MarketPredictionTransactionService predictionService;

    @Autowired
    private MarketMapper marketMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("market_test");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = TRUE", Integer.class))
                .isPositive();
        clearMarketData();
        seedMarket(MARKET_A, OPTION_A_YES, OPTION_A_NO, PREDICTION_A);
        seedMarket(MARKET_B, OPTION_B_YES, OPTION_B_NO, PREDICTION_B);
    }

    @Test
    @DisplayName("[실험4] 외부 지연을 트랜잭션 밖에 둘 때 DB 락 구간 단축")
    void externalDelayOutsideTransactionShortensMeasuredLockSection() throws InterruptedException {
        Thread.sleep(EXTERNAL_DELAY_MS);
        long aStart = System.nanoTime();
        predictionService.confirmPrediction(PREDICTION_A);
        long lockSectionA = elapsedMillis(aStart);

        long bStart = System.nanoTime();
        runTestOnlySlowPathWithDelayInsideLockedTransaction(MARKET_B, PREDICTION_B);
        long lockSectionB = elapsedMillis(bStart);

        long improvementPercent = Math.max(0, (lockSectionB - lockSectionA) * 100 / lockSectionB);

        System.out.println("[실험4] 외부지연 2000ms 주입: 락구간 A="
                + lockSectionA + "ms vs B=" + lockSectionB + "ms (개선 " + improvementPercent + "%)");

        assertThat(statusOf(PREDICTION_A)).isEqualTo(PredictionStatus.CONFIRMED.name());
        assertThat(lockSectionB).isGreaterThanOrEqualTo(EXTERNAL_DELAY_MS);
        assertThat(lockSectionA).isLessThan(lockSectionB);
    }

    private void runTestOnlySlowPathWithDelayInsideLockedTransaction(long marketId, long predictionId) {
        transactionTemplate.executeWithoutResult(status -> {
            marketMapper.lockMarketById(marketId);
            marketMapper.lockOptionsByMarketId(marketId);
            marketMapper.lockPredictionById(predictionId);
            sleep(EXTERNAL_DELAY_MS);
        });
    }

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

    private void seedMarket(long marketId, long optionYes, long optionNo, long predictionId) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                INSERT INTO market (
                    id, title, category, answer_type, judge_data_source, judge_criteria, judge_date,
                    status, close_at, total_pool, initial_virtual_liquidity, created_by, created_at, updated_at
                ) VALUES (?, 'Transaction Boundary Market', 'PRICE_INDEX', 'YES_NO', 'TEST', 'TEST',
                          ?, 'ACTIVE', ?, 0.00, 200.00, 1, ?, ?)
                """,
                marketId,
                LocalDate.now(),
                now.plusDays(1),
                now,
                now
        );
        insertOption(marketId, optionYes, "YES", "예", 1);
        insertOption(marketId, optionNo, "NO", "아니오", 2);
        insertPendingPrediction(predictionId, marketId, optionYes);
    }

    private void insertOption(long marketId, long optionId, String code, String text, int displayOrder) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                INSERT INTO market_option (
                    id, market_id, option_code, option_text, display_order,
                    virtual_pool_amount, real_pool_amount, total_contract_quantity,
                    current_price, prediction_count, is_result, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 100.00, 0.00, 0.00000000, 0.50000000, 0, FALSE, ?, ?)
                """,
                optionId,
                marketId,
                code,
                text,
                displayOrder,
                now,
                now
        );
    }

    private void insertPendingPrediction(long predictionId, long marketId, long optionId) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                INSERT INTO market_prediction (
                    id, market_id, option_id, member_id, point_amount,
                    status, point_spend_idempotency_key, attempt_no, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 'POINT_PENDING', ?, 1, ?, ?)
                """,
                predictionId,
                marketId,
                optionId,
                predictionId,
                new BigDecimal("100.00"),
                "BOUNDARY_EXPERIMENT_KEY_" + predictionId,
                now,
                now
        );
    }

    private String statusOf(long predictionId) {
        return jdbc.queryForObject(
                "SELECT status FROM market_prediction WHERE id = ?",
                String.class,
                predictionId
        );
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

}
