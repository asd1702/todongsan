package com.todongsan.marketservice.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.todongsan.marketservice.global.exception.CustomException;
import com.todongsan.marketservice.global.exception.errorcode.MarketErrorCode;
import com.todongsan.marketservice.market.client.MemberPointClient;
import com.todongsan.marketservice.market.dto.request.CreatePredictionRequest;
import com.todongsan.marketservice.market.dto.response.CreatePredictionResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class MarketPredictionCreateConcurrencyTest {

    private static final long MARKET_ID = 1L;
    private static final BigDecimal BET = new BigDecimal("100.00");

    @Autowired
    private MarketPredictionService predictionService;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private MemberPointClient memberPointClient;

    @BeforeEach
    void setUp() {
        reset(memberPointClient);
        jdbc.update("DELETE FROM market_price_history");
        jdbc.update("DELETE FROM market_refund_detail");
        jdbc.update("DELETE FROM market_void");
        jdbc.update("DELETE FROM market_settlement_detail");
        jdbc.update("DELETE FROM market_settlement");
        jdbc.update("DELETE FROM market_prediction");
        jdbc.update("DELETE FROM market_option");
        jdbc.update("DELETE FROM market");
        seedActiveMarketWithOptions();
    }

    @Test
    void concurrentPredictionsFromDifferentMembersCreateOnePredictionEach() throws Exception {
        List<CallOutcome> outcomes = callConcurrently(101L, 102L);

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.failure()).isNull());
        assertThat(outcomes).extracting(outcome -> outcome.response().getStatus())
                .containsOnly(com.todongsan.marketservice.market.type.PredictionStatus.CONFIRMED);
        assertThat(predictionCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(DISTINCT member_id) FROM market_prediction WHERE market_id = ?",
                Integer.class,
                MARKET_ID
        )).isEqualTo(2);
        verify(memberPointClient, times(2)).spend(any());
    }

    @Test
    void concurrentPredictionsFromSameMemberKeepOnePredictionAndSpendOnce() throws Exception {
        List<CallOutcome> outcomes = callConcurrently(101L, 101L);

        assertThat(outcomes).filteredOn(outcome -> outcome.failure() == null).hasSize(1);
        assertThat(outcomes).filteredOn(outcome -> outcome.failure() != null).singleElement()
                .extracting(CallOutcome::failure)
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(MarketErrorCode.MARKET_ALREADY_PREDICTED);
        assertThat(predictionCount()).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM market_prediction WHERE market_id = ? AND member_id = ?",
                String.class,
                MARKET_ID,
                101L
        )).isEqualTo("CONFIRMED");
        verify(memberPointClient, times(1)).spend(any());
    }

    private List<CallOutcome> callConcurrently(long... memberIds) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(memberIds.length);
        CountDownLatch ready = new CountDownLatch(memberIds.length);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<CallOutcome>> futures = new ArrayList<>();
            for (long memberId : memberIds) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        return new CallOutcome(
                                predictionService.createPrediction(
                                        MARKET_ID,
                                        memberId,
                                        idempotencyKey(memberId),
                                        createRequest()
                                ),
                                null
                        );
                    } catch (RuntimeException e) {
                        return new CallOutcome(null, e);
                    }
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<CallOutcome> outcomes = new ArrayList<>();
            for (Future<CallOutcome> future : futures) {
                outcomes.add(future.get(15, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            executor.shutdownNow();
        }
    }

    private int predictionCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM market_prediction WHERE market_id = ?",
                Integer.class,
                MARKET_ID
        );
    }

    private String idempotencyKey(long memberId) {
        return "MARKET_PREDICTION_SPEND:market:%d:member:%d".formatted(MARKET_ID, memberId);
    }

    private CreatePredictionRequest createRequest() {
        CreatePredictionRequest request = new CreatePredictionRequest();
        request.setMarketOptionId(1L);
        request.setPointAmount(BET);
        return request;
    }

    private void seedActiveMarketWithOptions() {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                INSERT INTO market (
                    id, title, category, answer_type, judge_data_source, judge_criteria, judge_date,
                    status, close_at, total_pool, initial_virtual_liquidity, created_by, created_at, updated_at
                ) VALUES (?, 'Prediction Concurrency Market', 'PRICE_INDEX', 'YES_NO', 'TEST', 'TEST',
                          ?, 'ACTIVE', ?, 0.00, 200.00, 1, ?, ?)
                """,
                MARKET_ID, LocalDate.now(), now.plusDays(1), now, now);
        seedOption(1L, "YES", 1, now);
        seedOption(2L, "NO", 2, now);
    }

    private void seedOption(long optionId, String code, int order, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO market_option (
                    id, market_id, option_code, option_text, display_order,
                    virtual_pool_amount, real_pool_amount, total_contract_quantity,
                    current_price, prediction_count, is_result, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 100.00, 0.00, 0.00000000, 0.50000000, 0, FALSE, ?, ?)
                """,
                optionId, MARKET_ID, code, code, order, now, now);
    }

    private record CallOutcome(CreatePredictionResponse response, RuntimeException failure) {
    }
}
