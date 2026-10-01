package com.todongsan.marketservice.market.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.todongsan.marketservice.market.client.MemberPointClient;
import com.todongsan.marketservice.market.service.PointResultApplyOutcome;
import com.todongsan.marketservice.market.service.PointResultBatchApplier;
import com.todongsan.marketservice.market.service.PointResultBatchApplier.Item;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** 결과 배치 반영 (ASYNC_POINT_FLOW.md §16.1). 환불 마켓 100과 정산 마켓 200의 결과를 한 배치로 반영한다. */
@SpringBootTest(properties = "market.point-transport.mode=async")
@ActiveProfiles("mysqlit")
class PointResultBatchApplierTest {

    private static final String REFUND = PointResultBatchApplier.MARKET_REFUND_REQUESTED;
    private static final String SETTLEMENT = PointResultBatchApplier.MARKET_SETTLEMENT_PAYOUT_REQUESTED;
    private static final long REFUND_MARKET = 100L;
    private static final long SETTLEMENT_MARKET = 200L;

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PointResultBatchApplier batchApplier;

    @MockitoBean
    private MemberPointClient memberPointClient;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .defaultRequest(get("/").header("X-Member-Role", "ADMIN"))
                .build();
        for (String table : List.of("market_outbox_event", "market_price_history", "market_reputation_update",
                "market_refund_detail", "market_settlement_detail", "market_settlement", "market_void",
                "market_prediction", "market_option", "market")) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        // 환불 마켓 100: 예측 1001(member 1), 1002(member 2)
        insertMarket(REFUND_MARKET, "VOIDED", null);
        insertOption(101L, REFUND_MARKET, "A", false);
        insertPrediction(1001L, REFUND_MARKET, 101L, 1L);
        insertPrediction(1002L, REFUND_MARKET, 101L, 2L);
        jdbcTemplate.update("""
                INSERT INTO market_void (id, market_id, reason_type, reason_detail, refund_status, voided_at, created_at, updated_at)
                VALUES (500, ?, 'DATA_UNAVAILABLE', 'test', 'PENDING', NOW(), NOW(), NOW())
                """, REFUND_MARKET);
        mockMvc.perform(post("/api/v1/admin/markets/{id}/refunds", REFUND_MARKET))
                .andExpect(jsonPath("$.data.refundStatus").value("IN_PROGRESS"));

        // 정산 마켓 200: 승자 2001(member 3), 패자 2002(member 4)
        insertMarket(SETTLEMENT_MARKET, "CLOSED", 201L);
        insertOption(201L, SETTLEMENT_MARKET, "A", true);
        insertOption(202L, SETTLEMENT_MARKET, "B", false);
        insertPrediction(2001L, SETTLEMENT_MARKET, 201L, 3L);
        insertPrediction(2002L, SETTLEMENT_MARKET, 202L, 4L);
        mockMvc.perform(post("/api/v1/admin/markets/{id}/settlements", SETTLEMENT_MARKET))
                .andExpect(jsonPath("$.data.settlementStatus").value("IN_PROGRESS"));
    }

    @Test
    void appliesRefundAndSettlementResultsInOneBatchAndCompletesEachOnce() {
        List<PointResultApplyOutcome> outcomes = batchApplier.applyBatch(List.of(
                item(SETTLEMENT, SETTLEMENT_MARKET, settlementKey(2001, 3), true),
                item(REFUND, REFUND_MARKET, refundKey(1001, 1), true),
                item(REFUND, REFUND_MARKET, refundKey(1002, 2), true)
        ));

        assertThat(outcomes).containsOnly(PointResultApplyOutcome.APPLIED);
        assertThat(queryString("SELECT refund_status FROM market_void WHERE id = 500")).isEqualTo("COMPLETED");
        assertThat(queryString("SELECT status FROM market WHERE id = 200")).isEqualTo("SETTLED");
        assertThat(queryString("SELECT status FROM market_settlement WHERE market_id = 200")).isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForList("SELECT status FROM market_prediction ORDER BY id", String.class))
                .containsExactly("REFUNDED", "REFUNDED", "SETTLED", "SETTLED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM market_reputation_update WHERE market_id = 200", Integer.class)).isEqualTo(2);
    }

    @Test
    void sameKeyFailedThenCreditedInOneBatchEndsInSuccess() {
        List<PointResultApplyOutcome> outcomes = batchApplier.applyBatch(List.of(
                item(REFUND, REFUND_MARKET, refundKey(1001, 1), false),
                item(REFUND, REFUND_MARKET, refundKey(1001, 1), true)
        ));

        assertThat(outcomes).containsExactly(PointResultApplyOutcome.APPLIED, PointResultApplyOutcome.APPLIED);
        assertThat(queryString("SELECT status FROM market_refund_detail WHERE prediction_id = 1001")).isEqualTo("SUCCESS");
    }

    @Test
    void lateFailedResultAfterSuccessInOneBatchIsIgnored() {
        List<PointResultApplyOutcome> outcomes = batchApplier.applyBatch(List.of(
                item(REFUND, REFUND_MARKET, refundKey(1001, 1), true),
                item(REFUND, REFUND_MARKET, refundKey(1001, 1), false)
        ));

        assertThat(outcomes).containsExactly(PointResultApplyOutcome.APPLIED, PointResultApplyOutcome.SKIPPED);
        assertThat(queryString("SELECT status FROM market_refund_detail WHERE prediction_id = 1001")).isEqualTo("SUCCESS");
    }

    @Test
    void missingDetailIsReturnedAsOutcomeWhileOthersAreCommitted() {
        List<PointResultApplyOutcome> outcomes = batchApplier.applyBatch(List.of(
                item(REFUND, REFUND_MARKET, refundKey(1001, 1), true),
                item(REFUND, REFUND_MARKET, refundKey(9999, 9), true),
                item(REFUND, 999L, refundKey(1002, 2), true)
        ));

        assertThat(outcomes).containsExactly(PointResultApplyOutcome.APPLIED, PointResultApplyOutcome.DETAIL_NOT_FOUND,
                PointResultApplyOutcome.DETAIL_NOT_FOUND);
        assertThat(queryString("SELECT status FROM market_refund_detail WHERE prediction_id = 1001")).isEqualTo("SUCCESS");
        assertThat(queryString("SELECT refund_status FROM market_void WHERE id = 500")).isEqualTo("IN_PROGRESS");
    }

    private Item item(String type, long marketId, String key, boolean credited) {
        return new Item(type, marketId, key, credited, credited ? null : "MEMBER_NOT_FOUND");
    }

    private static String refundKey(long predictionId, long memberId) {
        return "MARKET_REFUND:market:100:prediction:%d:member:%d".formatted(predictionId, memberId);
    }

    private static String settlementKey(long predictionId, long memberId) {
        return "MARKET_SETTLEMENT_REWARD:market:200:prediction:%d:member:%d".formatted(predictionId, memberId);
    }

    private String queryString(String sql) {
        return jdbcTemplate.queryForObject(sql, String.class);
    }

    private void insertMarket(long id, String status, Long resultOptionId) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                INSERT INTO market (id, title, category, answer_type, judge_data_source, judge_criteria, judge_date,
                                    status, close_at, result_option_id, total_pool, fee_rate, fee_amount, settlement_pool,
                                    initial_virtual_liquidity, created_by, created_at, updated_at)
                VALUES (?, 'Batch Test Market', 'PRICE_INDEX', 'MULTIPLE_CHOICE', 'TEST', 'TEST', ?, ?, ?, ?, 0.00, 5.00,
                        0.00, 0.00, 200.00, 1, ?, ?)
                """, id, LocalDate.now(), status, now.minusDays(1), resultOptionId, now, now);
    }

    private void insertOption(long id, long marketId, String code, boolean isResult) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                INSERT INTO market_option (id, market_id, option_code, option_text, display_order, virtual_pool_amount,
                                           real_pool_amount, total_contract_quantity, current_price, prediction_count,
                                           is_result, created_at, updated_at)
                VALUES (?, ?, ?, ?, 1, 100.00, 0.00, 0.00000000, 0.50000000, 0, ?, ?, ?)
                """, id, marketId, code, code, isResult, now, now);
    }

    private void insertPrediction(long id, long marketId, long optionId, long memberId) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                INSERT INTO market_prediction (id, market_id, option_id, member_id, point_amount, price_snapshot,
                                               contract_quantity, status, point_spend_idempotency_key, attempt_no,
                                               created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 0.50000000, 200.00000000, 'CONFIRMED', ?, 1, ?, ?)
                """, id, marketId, optionId, memberId, new BigDecimal("100.00"), "BATCH_TEST_KEY_" + id, now, now);
    }
}
