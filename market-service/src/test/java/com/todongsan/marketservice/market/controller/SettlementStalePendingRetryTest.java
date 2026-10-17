package com.todongsan.marketservice.market.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.todongsan.marketservice.market.client.MemberPointClient;
import com.todongsan.marketservice.market.client.MemberPointSettlementBatchRequest;
import com.todongsan.marketservice.market.client.MemberPointSettlementBatchResponse;
import com.todongsan.marketservice.market.client.MemberPointSettlementItemResult;
import com.todongsan.marketservice.market.client.MemberPointSettlementItemStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 정산 detail이 PENDING으로 정체된 경우 (ASYNC_POINT_FLOW.md §14 S3, sync 모드).
 * 정산 준비 커밋 후 Member-Point 호출 전에 서버가 종료된 상황을, 처리되지 않는 예외로 재현한다.
 */
@SpringBootTest
class SettlementStalePendingRetryTest {

    private static final long MARKET_ID = 100L;
    private static final long WIN_OPTION_ID = 101L;
    private static final long LOSE_OPTION_ID = 102L;

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private MemberPointClient memberPointClient;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .defaultRequest(get("/").header("X-Member-Role", "ADMIN"))
                .build();
        reset(memberPointClient);
        jdbcTemplate.update("DELETE FROM market_outbox_event");
        jdbcTemplate.update("DELETE FROM market_price_history");
        jdbcTemplate.update("DELETE FROM market_reputation_update");
        jdbcTemplate.update("DELETE FROM market_refund_detail");
        jdbcTemplate.update("DELETE FROM market_settlement_detail");
        jdbcTemplate.update("DELETE FROM market_settlement");
        jdbcTemplate.update("DELETE FROM market_void");
        jdbcTemplate.update("DELETE FROM market_prediction");
        jdbcTemplate.update("DELETE FROM market_option");
        jdbcTemplate.update("DELETE FROM market");

        insertSettlementMarket();
        insertSettlementOption(WIN_OPTION_ID, "A", true);
        insertSettlementOption(LOSE_OPTION_ID, "B", false);
        insertPrediction(1001L, WIN_OPTION_ID, 1L);
        insertPrediction(1002L, LOSE_OPTION_ID, 2L);

        // 준비 트랜잭션 커밋 후 Member-Point 호출 단계에서 중단
        when(memberPointClient.settleMarketRewards(anyString(), any(MemberPointSettlementBatchRequest.class)))
                .thenThrow(new IllegalStateException("server stopped before member-point call"));
        mockMvc.perform(post("/api/v1/admin/markets/{marketId}/settlements", MARKET_ID))
                .andExpect(status().is5xxServerError());
        assertThat(detailStatus()).isEqualTo("PENDING");
        reset(memberPointClient);
    }

    @Test
    void freshPendingIsNotRetriedYet() throws Exception {
        mockMvc.perform(post("/api/v1/admin/markets/{marketId}/settlements/retry", MARKET_ID))
                .andExpect(jsonPath("$.errorCode").value("MARKET_INVALID_SETTLEMENT_DATA"));
    }

    @Test
    void stalePendingIsShownAsProblemAndRetriedToCompletion() throws Exception {
        ageDetail();

        mockMvc.perform(get("/api/v1/admin/markets/problem-markets").param("type", "SETTLEMENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$..marketId", hasItem((int) MARKET_ID)))
                .andExpect(jsonPath("$..pendingStaleCount", hasItem(1)));

        stubProcessed();
        mockMvc.perform(post("/api/v1/admin/markets/{marketId}/settlements/retry", MARKET_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.marketStatus").value("SETTLED"))
                .andExpect(jsonPath("$.data.settlementStatus").value("COMPLETED"));

        assertThat(detailStatus()).isEqualTo("SUCCESS");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM market_prediction WHERE id = 1001", String.class)).isEqualTo("SETTLED");
        // 중단 시점에 CONFIRMED로 남았던 패자도 재시도에서 정산되고 reputation task가 만들어진다
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM market_prediction WHERE id = 1002", String.class)).isEqualTo("SETTLED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM market_reputation_update WHERE market_id = ?", Integer.class, MARKET_ID))
                .isEqualTo(2);
    }

    private void ageDetail() {
        jdbcTemplate.update("UPDATE market_settlement_detail SET updated_at = ?", LocalDateTime.now().minusMinutes(10));
    }

    private String detailStatus() {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM market_settlement_detail WHERE prediction_id = 1001", String.class);
    }

    private void stubProcessed() {
        when(memberPointClient.settleMarketRewards(anyString(), any(MemberPointSettlementBatchRequest.class)))
                .thenAnswer(invocation -> {
                    MemberPointSettlementBatchRequest request = invocation.getArgument(1);
                    return new MemberPointSettlementBatchResponse(
                            request.marketId(),
                            request.items().stream()
                                    .map(item -> new MemberPointSettlementItemResult(
                                            item.predictionId(),
                                            item.memberId(),
                                            MemberPointSettlementItemStatus.PROCESSED,
                                            null,
                                            item.amount(),
                                            null
                                    ))
                                    .toList()
                    );
                });
    }

    private void insertSettlementMarket() {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                INSERT INTO market (
                    id, title, category, answer_type, judge_data_source, judge_criteria, judge_date,
                    status, close_at, result_option_id, total_pool, fee_rate, fee_amount,
                    settlement_pool, initial_virtual_liquidity, created_by, created_at, updated_at
                ) VALUES (?, 'Stale Pending Settlement Market', 'PRICE_INDEX', 'MULTIPLE_CHOICE', 'TEST', 'TEST',
                          ?, 'CLOSED', ?, ?, 0.00, 5.00, 0.00, 0.00, 200.00, 1, ?, ?)
                """,
                MARKET_ID, LocalDate.now(), now.minusDays(1), WIN_OPTION_ID, now, now
        );
    }

    private void insertSettlementOption(long optionId, String optionCode, boolean isResult) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                INSERT INTO market_option (
                    id, market_id, option_code, option_text, display_order,
                    virtual_pool_amount, real_pool_amount, total_contract_quantity,
                    current_price, prediction_count, is_result, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 100.00, 0.00, 0.00000000, 0.50000000, 0, ?, ?, ?)
                """,
                optionId, MARKET_ID, optionCode, optionCode, optionId, isResult, now, now
        );
    }

    private void insertPrediction(long predictionId, long optionId, long memberId) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                INSERT INTO market_prediction (
                    id, market_id, option_id, member_id, point_amount, price_snapshot, contract_quantity,
                    status, point_spend_idempotency_key, attempt_no, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 100.00, 0.50000000, 200.00000000, 'CONFIRMED', ?, 1, ?, ?)
                """,
                predictionId, MARKET_ID, optionId, memberId, "STALE_PENDING_TEST_KEY_" + predictionId, now, now
        );
    }
}
