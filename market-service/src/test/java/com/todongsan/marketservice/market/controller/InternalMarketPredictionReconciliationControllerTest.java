package com.todongsan.marketservice.market.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.todongsan.marketservice.market.client.MemberPointClient;
import com.todongsan.marketservice.market.client.MemberPointTransactionStatus;
import com.todongsan.marketservice.market.client.MemberPointTransactionStatusResponse;
import com.todongsan.marketservice.market.client.PointSpendCommand;
import com.todongsan.marketservice.market.client.exception.MemberPointTimeoutException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class InternalMarketPredictionReconciliationControllerTest {

    private static final long MARKET_ID = 1L;
    private static final long OPTION_ID = 11L;
    private static final LocalDateTime OLD_UPDATED_AT = LocalDateTime.now().minusMinutes(5);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private MemberPointClient memberPointClient;

    @BeforeEach
    void setUp() {
        reset(memberPointClient);
        jdbcTemplate.update("DELETE FROM market_price_history");
        jdbcTemplate.update("DELETE FROM market_refund_detail");
        jdbcTemplate.update("DELETE FROM market_settlement_detail");
        jdbcTemplate.update("DELETE FROM market_settlement");
        jdbcTemplate.update("DELETE FROM market_void");
        jdbcTemplate.update("DELETE FROM market_prediction");
        jdbcTemplate.update("DELETE FROM market_option");
        jdbcTemplate.update("DELETE FROM market");
    }

    @Test
    void reconcilePointUnknownProcessedConfirmsPredictionAndRecalculatesPrices() throws Exception {
        insertMarket(MARKET_ID, "ACTIVE", LocalDateTime.now().plusDays(1));
        insertOptions(MARKET_ID);
        insertPrediction(100L, MARKET_ID, OPTION_ID, 10L, "POINT_UNKNOWN", OLD_UPDATED_AT);
        stubStatus(key(MARKET_ID, 10L), MemberPointTransactionStatus.PROCESSED, null);

        mockMvc.perform(post("/api/v1/internal/markets/predictions/reconcile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requestedLimit").value(100))
                .andExpect(jsonPath("$.data.scannedCount").value(1))
                .andExpect(jsonPath("$.data.processedCount").value(1))
                .andExpect(jsonPath("$.data.confirmedCount").value(1))
                .andExpect(jsonPath("$.data.failedCount").value(0))
                .andExpect(jsonPath("$.data.unknownCount").value(0))
                .andExpect(jsonPath("$.data.skippedCount").value(0));

        assertPredictionConfirmed(100L);
        assertThat(jdbcTemplate.queryForObject("SELECT total_pool FROM market WHERE id = ?", String.class, MARKET_ID))
                .isEqualTo("100.00");
        assertThat(jdbcTemplate.queryForList(
                "SELECT real_pool_amount FROM market_option WHERE market_id = ? ORDER BY id",
                String.class,
                MARKET_ID
        )).containsExactly("100.00", "0.00");
        assertThat(jdbcTemplate.queryForList(
                "SELECT current_price FROM market_option WHERE market_id = ? ORDER BY id",
                String.class,
                MARKET_ID
        )).containsExactly("0.66666667", "0.33333333");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM market_price_history", Integer.class))
                .isEqualTo(2);
        verify(memberPointClient).getTransactionStatus(key(MARKET_ID, 10L));
    }

    @Test
    void reconcileOldPointPendingProcessedAllowsConfirmationAfterCloseAt() throws Exception {
        insertMarket(MARKET_ID, "ACTIVE", LocalDateTime.now().minusMinutes(1));
        insertOptions(MARKET_ID);
        insertPrediction(100L, MARKET_ID, OPTION_ID, 10L, "POINT_PENDING", OLD_UPDATED_AT);
        stubStatus(key(MARKET_ID, 10L), MemberPointTransactionStatus.PROCESSED, null);

        mockMvc.perform(post("/api/v1/internal/markets/predictions/reconcile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.confirmedCount").value(1));

        assertPredictionConfirmed(100L);
    }

    @Test
    void reconcileDataPendingProcessedConfirmsPrediction() throws Exception {
        insertMarket(MARKET_ID, "DATA_PENDING", LocalDateTime.now().minusMinutes(1));
        insertOptions(MARKET_ID);
        insertPrediction(100L, MARKET_ID, OPTION_ID, 10L, "POINT_UNKNOWN", OLD_UPDATED_AT);
        stubStatus(key(MARKET_ID, 10L), MemberPointTransactionStatus.PROCESSED, null);

        mockMvc.perform(post("/api/v1/internal/markets/predictions/reconcile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.confirmedCount").value(1));

        assertPredictionConfirmed(100L);
    }

    @Test
    void reconcileFailedMarksPredictionFailedWithoutPriceChanges() throws Exception {
        insertMarket(MARKET_ID, "ACTIVE", LocalDateTime.now().plusDays(1));
        insertOptions(MARKET_ID);
        insertPrediction(100L, MARKET_ID, OPTION_ID, 10L, "POINT_UNKNOWN", OLD_UPDATED_AT);
        stubStatus(key(MARKET_ID, 10L), MemberPointTransactionStatus.FAILED, "POINT_INSUFFICIENT");

        mockMvc.perform(post("/api/v1/internal/markets/predictions/reconcile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.failedCount").value(1));

        assertPredictionWithoutPriceConfirmation(100L, "FAILED", "POINT_INSUFFICIENT");
    }

    /** NOT_FOUND면 바로 FAILED로 종결하지 않고 먼저 펜싱해 늦게 도착한 spend를 막는다 (ASYNC_POINT_FLOW.md §12.6). */
    @Test
    void reconcileNotFoundFencesSpendBeforeMarkingPredictionFailed() throws Exception {
        insertMarket(MARKET_ID, "ACTIVE", LocalDateTime.now().plusDays(1));
        insertOptions(MARKET_ID);
        insertPrediction(100L, MARKET_ID, OPTION_ID, 10L, "POINT_UNKNOWN", OLD_UPDATED_AT);
        stubStatus(key(MARKET_ID, 10L), MemberPointTransactionStatus.NOT_FOUND, null);
        stubFence(key(MARKET_ID, 10L), MemberPointTransactionStatus.FAILED, "POINT_SPEND_FENCED");

        mockMvc.perform(post("/api/v1/internal/markets/predictions/reconcile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.failedCount").value(1))
                .andExpect(jsonPath("$.data.notFoundCount").value(1));

        assertPredictionWithoutPriceConfirmation(100L, "FAILED", "POINT_SPEND_FENCED");
        // 원래 spend와 같은 요청 내용 (MP request_hash 일치)
        verify(memberPointClient).fenceSpend(new PointSpendCommand(
                10L, "SPEND_MARKET", new BigDecimal("100.00"), "MARKET_PREDICTION", 100L, key(MARKET_ID, 10L)));
        verify(memberPointClient, org.mockito.Mockito.never()).spend(any(PointSpendCommand.class));
    }

    /** 조회와 펜싱 사이에 늦은 spend가 먼저 처리되면 펜싱은 PROCESSED를 돌려주고, 예측을 확정한다 (forward recovery). */
    @Test
    void reconcileNotFoundConfirmsPredictionWhenLateSpendWonTheFence() throws Exception {
        insertMarket(MARKET_ID, "ACTIVE", LocalDateTime.now().plusDays(1));
        insertOptions(MARKET_ID);
        insertPrediction(100L, MARKET_ID, OPTION_ID, 10L, "POINT_UNKNOWN", OLD_UPDATED_AT);
        stubStatus(key(MARKET_ID, 10L), MemberPointTransactionStatus.NOT_FOUND, null);
        stubFence(key(MARKET_ID, 10L), MemberPointTransactionStatus.PROCESSED, null);

        mockMvc.perform(post("/api/v1/internal/markets/predictions/reconcile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.confirmedCount").value(1))
                .andExpect(jsonPath("$.data.failedCount").value(0))
                .andExpect(jsonPath("$.data.notFoundCount").value(1));

        assertPredictionConfirmed(100L);
    }

    @Test
    void reconcileNotFoundKeepsPointUnknownWhenFenceFails() throws Exception {
        insertMarket(MARKET_ID, "ACTIVE", LocalDateTime.now().plusDays(1));
        insertOptions(MARKET_ID);
        insertPrediction(100L, MARKET_ID, OPTION_ID, 10L, "POINT_UNKNOWN", OLD_UPDATED_AT);
        stubStatus(key(MARKET_ID, 10L), MemberPointTransactionStatus.NOT_FOUND, null);
        when(memberPointClient.fenceSpend(any(PointSpendCommand.class)))
                .thenThrow(new MemberPointTimeoutException("timeout"));

        mockMvc.perform(post("/api/v1/internal/markets/predictions/reconcile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.failedCount").value(0))
                .andExpect(jsonPath("$.data.unknownCount").value(1));

        // 이미 POINT_UNKNOWN이면 상태·사유를 그대로 두고, 다음 대사에서 다시 조회·펜싱한다
        assertPredictionWithoutPriceConfirmation(100L, "POINT_UNKNOWN", null);
    }

    @Test
    void reconcileUnknownConvertsPendingToPointUnknown() throws Exception {
        insertMarket(MARKET_ID, "ACTIVE", LocalDateTime.now().plusDays(1));
        insertOptions(MARKET_ID);
        insertPrediction(100L, MARKET_ID, OPTION_ID, 10L, "POINT_PENDING", OLD_UPDATED_AT);
        stubStatus(key(MARKET_ID, 10L), MemberPointTransactionStatus.UNKNOWN, null);

        mockMvc.perform(post("/api/v1/internal/markets/predictions/reconcile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unknownCount").value(1));

        assertPredictionWithoutPriceConfirmation(100L, "POINT_UNKNOWN", "MEMBER_POINT_RESULT_UNKNOWN");
    }

    @Test
    void reconcileTimeoutKeepsApiSuccessfulAndMarksUnknown() throws Exception {
        insertMarket(MARKET_ID, "ACTIVE", LocalDateTime.now().plusDays(1));
        insertOptions(MARKET_ID);
        insertPrediction(100L, MARKET_ID, OPTION_ID, 10L, "POINT_PENDING", OLD_UPDATED_AT);
        when(memberPointClient.getTransactionStatus(key(MARKET_ID, 10L)))
                .thenThrow(new MemberPointTimeoutException("timeout"));

        mockMvc.perform(post("/api/v1/internal/markets/predictions/reconcile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unknownCount").value(1))
                .andExpect(jsonPath("$.data.skippedCount").value(0));

        assertPredictionWithoutPriceConfirmation(100L, "POINT_UNKNOWN", "timeout");
    }

    @Test
    void reconcileProcessedSkipsClosedMarket() throws Exception {
        insertMarket(MARKET_ID, "CLOSED", LocalDateTime.now().minusMinutes(1));
        insertOptions(MARKET_ID);
        insertPrediction(100L, MARKET_ID, OPTION_ID, 10L, "POINT_UNKNOWN", OLD_UPDATED_AT);
        stubStatus(key(MARKET_ID, 10L), MemberPointTransactionStatus.PROCESSED, null);

        mockMvc.perform(post("/api/v1/internal/markets/predictions/reconcile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.processedCount").value(1))
                .andExpect(jsonPath("$.data.confirmedCount").value(0))
                .andExpect(jsonPath("$.data.skippedCount").value(1));

        assertPredictionWithoutPriceConfirmation(100L, "POINT_UNKNOWN", null);
    }

    @Test
    void reconcileRecentPointPendingIsNotScanned() throws Exception {
        insertMarket(MARKET_ID, "ACTIVE", LocalDateTime.now().plusDays(1));
        insertOptions(MARKET_ID);
        insertPrediction(100L, MARKET_ID, OPTION_ID, 10L, "POINT_PENDING", LocalDateTime.now());

        mockMvc.perform(post("/api/v1/internal/markets/predictions/reconcile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scannedCount").value(0))
                .andExpect(jsonPath("$.data.processedCount").value(0));

        verifyNoInteractions(memberPointClient);
    }

    @Test
    void reconcileRejectsInvalidLimit() throws Exception {
        mockMvc.perform(post("/api/v1/internal/markets/predictions/reconcile?limit=0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/v1/internal/markets/predictions/reconcile?limit=501"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    void reconcileMixedBatchCountsEachResult() throws Exception {
        insertMarket(MARKET_ID, "ACTIVE", LocalDateTime.now().plusDays(1));
        insertOptions(MARKET_ID);
        insertMarket(2L, "CLOSED", LocalDateTime.now().minusMinutes(1));
        insertOptions(2L);
        insertPrediction(100L, MARKET_ID, OPTION_ID, 10L, "POINT_UNKNOWN", OLD_UPDATED_AT.minusSeconds(4));
        insertPrediction(101L, MARKET_ID, OPTION_ID, 11L, "POINT_UNKNOWN", OLD_UPDATED_AT.minusSeconds(3));
        insertPrediction(102L, MARKET_ID, OPTION_ID, 12L, "POINT_UNKNOWN", OLD_UPDATED_AT.minusSeconds(2));
        insertPrediction(103L, MARKET_ID, OPTION_ID, 13L, "POINT_UNKNOWN", OLD_UPDATED_AT.minusSeconds(1));
        insertPrediction(200L, 2L, 21L, 20L, "POINT_UNKNOWN", OLD_UPDATED_AT);
        stubStatus(key(MARKET_ID, 10L), MemberPointTransactionStatus.PROCESSED, null);
        stubStatus(key(MARKET_ID, 11L), MemberPointTransactionStatus.NOT_FOUND, null);
        stubFence(key(MARKET_ID, 11L), MemberPointTransactionStatus.FAILED, "POINT_SPEND_FENCED");
        stubStatus(key(MARKET_ID, 12L), MemberPointTransactionStatus.UNKNOWN, null);
        stubStatus(key(MARKET_ID, 13L), MemberPointTransactionStatus.FAILED, "MEMBER_NOT_FOUND");
        stubStatus(key(2L, 20L), MemberPointTransactionStatus.PROCESSED, null);

        mockMvc.perform(post("/api/v1/internal/markets/predictions/reconcile?limit=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requestedLimit").value(10))
                .andExpect(jsonPath("$.data.scannedCount").value(5))
                .andExpect(jsonPath("$.data.processedCount").value(5))
                .andExpect(jsonPath("$.data.confirmedCount").value(1))
                .andExpect(jsonPath("$.data.failedCount").value(2))
                .andExpect(jsonPath("$.data.notFoundCount").value(1))
                .andExpect(jsonPath("$.data.unknownCount").value(1))
                .andExpect(jsonPath("$.data.skippedCount").value(1));
    }

    private void stubStatus(String idempotencyKey, MemberPointTransactionStatus status, String errorCode) {
        when(memberPointClient.getTransactionStatus(eq(idempotencyKey)))
                .thenReturn(new MemberPointTransactionStatusResponse(
                        idempotencyKey,
                        status,
                        null,
                        "SPEND_MARKET",
                        null,
                        "MARKET_PREDICTION",
                        null,
                        null,
                        LocalDateTime.now(),
                        errorCode
                ));
    }

    private void stubFence(String idempotencyKey, MemberPointTransactionStatus status, String errorCode) {
        when(memberPointClient.fenceSpend(org.mockito.ArgumentMatchers.argThat(
                command -> command != null && idempotencyKey.equals(command.idempotencyKey()))))
                .thenReturn(new MemberPointTransactionStatusResponse(
                        idempotencyKey,
                        status,
                        null,
                        "SPEND_MARKET",
                        null,
                        "MARKET_PREDICTION",
                        null,
                        null,
                        LocalDateTime.now(),
                        errorCode
                ));
    }

    private void insertMarket(long marketId, String status, LocalDateTime closeAt) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                INSERT INTO market (
                    id, title, category, answer_type, judge_data_source, judge_criteria, judge_date,
                    status, close_at, total_pool, initial_virtual_liquidity, created_by, created_at, updated_at
                ) VALUES (?, 'Reconcile Test Market', 'PRICE_INDEX', 'YES_NO', 'TEST', 'TEST',
                          ?, ?, ?, 0.00, 200.00, 1, ?, ?)
                """,
                marketId,
                LocalDate.now().plusDays(2),
                status,
                closeAt,
                now,
                now
        );
    }

    private void insertOptions(long marketId) {
        insertOption(optionId(marketId, 1L), marketId, "A", 1);
        insertOption(optionId(marketId, 2L), marketId, "B", 2);
    }

    private void insertOption(long optionId, long marketId, String optionCode, int displayOrder) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                INSERT INTO market_option (
                    id, market_id, option_code, option_text, display_order,
                    virtual_pool_amount, real_pool_amount, total_contract_quantity,
                    current_price, prediction_count, is_result, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 100.00, 0.00, 0.00000000, 0.50000000, 0, FALSE, ?, ?)
                """,
                optionId,
                marketId,
                optionCode,
                optionCode,
                displayOrder,
                now,
                now
        );
    }

    private void insertPrediction(
            long predictionId,
            long marketId,
            long optionId,
            long memberId,
            String status,
            LocalDateTime updatedAt
    ) {
        jdbcTemplate.update("""
                INSERT INTO market_prediction (
                    id, market_id, option_id, member_id, point_amount, status,
                    point_spend_idempotency_key, attempt_no, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 100.00, ?, ?, 1, ?, ?)
                """,
                predictionId,
                marketId,
                optionId,
                memberId,
                status,
                key(marketId, memberId),
                updatedAt.minusMinutes(1),
                updatedAt
        );
    }

    private void assertPredictionConfirmed(long predictionId) {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM market_prediction WHERE id = ?",
                String.class,
                predictionId
        )).isEqualTo("CONFIRMED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT price_snapshot FROM market_prediction WHERE id = ?",
                String.class,
                predictionId
        )).isEqualTo("0.50000000");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT contract_quantity FROM market_prediction WHERE id = ?",
                String.class,
                predictionId
        )).isEqualTo("200.00000000");
    }

    private void assertPredictionWithoutPriceConfirmation(long predictionId, String status, String failReason) {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM market_prediction WHERE id = ?",
                String.class,
                predictionId
        )).isEqualTo(status);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT fail_reason FROM market_prediction WHERE id = ?",
                String.class,
                predictionId
        )).isEqualTo(failReason);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT price_snapshot FROM market_prediction WHERE id = ?",
                String.class,
                predictionId
        )).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT contract_quantity FROM market_prediction WHERE id = ?",
                String.class,
                predictionId
        )).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM market_price_history", Integer.class))
                .isZero();
    }

    private long optionId(long marketId, long displayOrder) {
        return marketId * 10 + displayOrder;
    }

    private String key(long marketId, long memberId) {
        return "MARKET_PREDICTION_SPEND:market:%d:member:%d:attempt:1".formatted(marketId, memberId);
    }
}
