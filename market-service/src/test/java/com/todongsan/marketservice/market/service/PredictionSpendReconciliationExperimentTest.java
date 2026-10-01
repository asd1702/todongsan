package com.todongsan.marketservice.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.todongsan.marketservice.market.client.MemberPointClient;
import com.todongsan.marketservice.market.client.PointSpendCommand;
import com.todongsan.marketservice.market.client.MemberPointTransactionStatus;
import com.todongsan.marketservice.market.client.MemberPointTransactionStatusResponse;
import com.todongsan.marketservice.market.client.exception.MemberPointTimeoutException;
import com.todongsan.marketservice.market.dto.response.ReconcilePredictionSpendResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class PredictionSpendReconciliationExperimentTest {

    private static final long MARKET_ID = 2200L;
    private static final long OPTION_YES = 2201L;
    private static final long OPTION_NO = 2202L;

    @Autowired
    private PredictionSpendReconciliationService reconciliationService;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private MemberPointClient memberPointClient;

    @BeforeEach
    void setUp() {
        reset(memberPointClient);
        deleteAll();
    }

    @Test
    @DisplayName("[실험3] POINT_UNKNOWN/PENDING 대사 복구 분류 정확도")
    void reconciliationClassifiesPointSpendOutcomes() {
        seedActiveMarket();
        seedOptions();
        insertPrediction(4001L, OPTION_YES, 601L, "POINT_UNKNOWN", "RECON_KEY_PROCESSED", 10);
        insertPrediction(4002L, OPTION_NO, 602L, "POINT_PENDING", "RECON_KEY_FAILED", 10);
        insertPrediction(4003L, OPTION_YES, 603L, "POINT_PENDING", "RECON_KEY_NOT_FOUND", 10);
        insertPrediction(4004L, OPTION_NO, 604L, "POINT_UNKNOWN", "RECON_KEY_TIMEOUT", 10);
        stubTransactionStatuses();

        ReconcilePredictionSpendResponse response = reconciliationService.reconcile(10);

        int confirmed = countPredictionsByStatus("CONFIRMED");
        int failed = countPredictionsByStatus("FAILED");
        int unknown = countPredictionsByStatus("POINT_UNKNOWN");
        int stuckPending = countPredictionsByStatus("POINT_PENDING");
        int correctlyClassified = confirmed + failed + unknown;

        System.out.println("[실험3] 대사: 대상 " + response.scannedCount()
                + " / CONFIRMED " + confirmed
                + " / FAILED " + failed
                + " / UNKNOWN유지 " + unknown
                + " / 분류정확 " + correctlyClassified + "/" + response.scannedCount()
                + " / 고착 " + stuckPending);

        assertThat(response.scannedCount()).isEqualTo(4);
        assertThat(response.processedCount()).isEqualTo(4);
        assertThat(response.confirmedCount()).isEqualTo(1);
        assertThat(response.failedCount()).isEqualTo(2);
        assertThat(response.notFoundCount()).isEqualTo(1);
        assertThat(response.unknownCount()).isEqualTo(1);
        assertThat(confirmed).isEqualTo(1);
        assertThat(failed).isEqualTo(2);
        assertThat(unknown).isEqualTo(1);
        assertThat(stuckPending).isZero();
        assertThat(statusOf(4001L)).isEqualTo("CONFIRMED");
        assertThat(statusOf(4002L)).isEqualTo("FAILED");
        assertThat(statusOf(4003L)).isEqualTo("FAILED");
        assertThat(statusOf(4004L)).isEqualTo("POINT_UNKNOWN");
    }

    private void stubTransactionStatuses() {
        when(memberPointClient.getTransactionStatus(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return switch (key) {
                case "RECON_KEY_PROCESSED" -> transactionStatus(key, MemberPointTransactionStatus.PROCESSED, null);
                case "RECON_KEY_FAILED" -> transactionStatus(key, MemberPointTransactionStatus.FAILED, "POINT_FAILED");
                case "RECON_KEY_NOT_FOUND" -> transactionStatus(key, MemberPointTransactionStatus.NOT_FOUND, null);
                case "RECON_KEY_TIMEOUT" -> throwTimeout();
                default -> transactionStatus(key, MemberPointTransactionStatus.NOT_FOUND, null);
            };
        });
        // NOT_FOUND는 펜싱 후 FAILED로 확정된다 (MP: 기록이 없으면 POINT_SPEND_FENCED로 기록, ASYNC_POINT_FLOW.md §12.6)
        when(memberPointClient.fenceSpend(any(PointSpendCommand.class))).thenAnswer(invocation -> transactionStatus(
                invocation.<PointSpendCommand>getArgument(0).idempotencyKey(),
                MemberPointTransactionStatus.FAILED,
                "POINT_SPEND_FENCED"
        ));
    }

    private MemberPointTransactionStatusResponse transactionStatus(
            String key,
            MemberPointTransactionStatus status,
            String errorCode
    ) {
        return new MemberPointTransactionStatusResponse(
                key,
                status,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                errorCode
        );
    }

    private MemberPointTransactionStatusResponse throwTimeout() {
        throw new MemberPointTimeoutException("MEMBER_POINT_TIMEOUT");
    }

    private void seedActiveMarket() {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                INSERT INTO market (
                    id, title, category, answer_type, judge_data_source, judge_criteria, judge_date,
                    status, close_at, total_pool, initial_virtual_liquidity, created_by, created_at, updated_at
                ) VALUES (?, 'Experiment Reconciliation Market', 'PRICE_INDEX', 'YES_NO', 'TEST', 'TEST',
                          ?, 'ACTIVE', ?, 0.00, 200.00, 1, ?, ?)
                """,
                MARKET_ID,
                LocalDate.now(),
                now.plusDays(1),
                now,
                now
        );
    }

    private void seedOptions() {
        insertOption(OPTION_YES, "YES", "예", 1);
        insertOption(OPTION_NO, "NO", "아니오", 2);
    }

    private void insertOption(long optionId, String code, String text, int displayOrder) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                INSERT INTO market_option (
                    id, market_id, option_code, option_text, display_order,
                    virtual_pool_amount, real_pool_amount, total_contract_quantity,
                    current_price, prediction_count, is_result, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 100.00, 0.00, 0.00000000, 0.50000000, 0, FALSE, ?, ?)
                """,
                optionId,
                MARKET_ID,
                code,
                text,
                displayOrder,
                now,
                now
        );
    }

    private void insertPrediction(
            long predictionId,
            long optionId,
            long memberId,
            String status,
            String idempotencyKey,
            int updatedMinutesAgo
    ) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime updatedAt = now.minusMinutes(updatedMinutesAgo);
        jdbc.update("""
                INSERT INTO market_prediction (
                    id, market_id, option_id, member_id, point_amount,
                    status, point_spend_idempotency_key, attempt_no, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 100.00, ?, ?, 1, ?, ?)
                """,
                predictionId,
                MARKET_ID,
                optionId,
                memberId,
                status,
                idempotencyKey,
                now,
                updatedAt
        );
    }

    private int countPredictionsByStatus(String status) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM market_prediction WHERE market_id = ? AND status = ?",
                Integer.class,
                MARKET_ID,
                status
        );
    }

    private String statusOf(long predictionId) {
        return jdbc.queryForObject(
                "SELECT status FROM market_prediction WHERE id = ?",
                String.class,
                predictionId
        );
    }

    private void deleteAll() {
        jdbc.update("DELETE FROM market_price_history");
        jdbc.update("DELETE FROM market_reputation_update");
        jdbc.update("DELETE FROM market_refund_detail");
        jdbc.update("DELETE FROM market_settlement_detail");
        jdbc.update("DELETE FROM market_settlement");
        jdbc.update("DELETE FROM market_void");
        jdbc.update("DELETE FROM market_prediction");
        jdbc.update("DELETE FROM market_option");
        jdbc.update("DELETE FROM market");
    }
}
