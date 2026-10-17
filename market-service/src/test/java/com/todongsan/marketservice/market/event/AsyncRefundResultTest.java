package com.todongsan.marketservice.market.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.todongsan.marketservice.market.client.MemberPointClient;
import com.todongsan.marketservice.market.service.MarketRefundTransactionService;
import com.todongsan.marketservice.market.service.PointResultApplyOutcome;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** 결과 반영 규칙(ASYNC_POINT_FLOW.md §13.3), 완료 판정, R5 수동 재시도(§13.4). */
@SpringBootTest(properties = "market.point-transport.mode=async")
class AsyncRefundResultTest {

    private static final long MARKET_ID = 100L;
    private static final long OPTION_ID = 101L;
    private static final long VOID_ID = 500L;
    private static final String KEY_1 = "MARKET_REFUND:market:100:prediction:1001:member:1";
    private static final String KEY_2 = "MARKET_REFUND:market:100:prediction:1002:member:2";

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MarketRefundTransactionService refundTransactionService;

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
        jdbcTemplate.update("DELETE FROM market_refund_detail");
        jdbcTemplate.update("DELETE FROM market_settlement_detail");
        jdbcTemplate.update("DELETE FROM market_settlement");
        jdbcTemplate.update("DELETE FROM market_void");
        jdbcTemplate.update("DELETE FROM market_prediction");
        jdbcTemplate.update("DELETE FROM market_option");
        jdbcTemplate.update("DELETE FROM market");

        insertVoidedMarketWithVoid();
        insertOption();
        insertPrediction(1001L, 1L, "100.00");
        insertPrediction(1002L, 2L, "50.00");
        mockMvc.perform(post("/api/v1/admin/markets/{marketId}/refunds", MARKET_ID))
                .andExpect(jsonPath("$.data.refundStatus").value("IN_PROGRESS"));
    }

    @Test
    void allCreditedResultsCompleteRefund() {
        assertThat(apply(KEY_1, true, null)).isEqualTo(PointResultApplyOutcome.APPLIED);
        assertThat(voidRefundStatus()).isEqualTo("IN_PROGRESS");

        assertThat(apply(KEY_2, true, null)).isEqualTo(PointResultApplyOutcome.APPLIED);

        assertThat(detailStatuses()).containsExactly("SUCCESS", "SUCCESS");
        assertThat(predictionStatuses()).containsExactly("REFUNDED", "REFUNDED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT refund_amount FROM market_prediction WHERE id = 1002", BigDecimal.class))
                .isEqualByComparingTo("50.00");
        assertThat(voidRefundStatus()).isEqualTo("COMPLETED");
        verifyNoInteractions(memberPointClient);
    }

    @Test
    void failedResultKeepsRefundInProgress() {
        apply(KEY_1, true, null);

        assertThat(apply(KEY_2, false, "MEMBER_NOT_FOUND")).isEqualTo(PointResultApplyOutcome.APPLIED);

        assertThat(detailStatuses()).containsExactly("SUCCESS", "FAILED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT fail_reason FROM market_refund_detail WHERE idempotency_key = ?", String.class, KEY_2))
                .isEqualTo("MEMBER_NOT_FOUND");
        assertThat(predictionStatuses()).containsExactly("REFUNDED", "REFUND_PENDING");
        assertThat(voidRefundStatus()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void successIsFinalAgainstRedeliveryAndLateFailure() {
        apply(KEY_1, true, null);
        apply(KEY_2, true, null);

        assertThat(apply(KEY_1, true, null)).isEqualTo(PointResultApplyOutcome.SKIPPED);
        assertThat(apply(KEY_1, false, "IDEMPOTENCY_KEY_CONFLICT")).isEqualTo(PointResultApplyOutcome.SKIPPED);

        assertThat(detailStatuses()).containsExactly("SUCCESS", "SUCCESS");
        assertThat(predictionStatuses()).containsExactly("REFUNDED", "REFUNDED");
        assertThat(voidRefundStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void unknownIdempotencyKeyIsReportedAsMissingDetail() {
        assertThat(apply("MARKET_REFUND:market:100:prediction:9999:member:9", true, null))
                .isEqualTo(PointResultApplyOutcome.DETAIL_NOT_FOUND);
    }

    @Test
    void manualRetryRepublishesFailedDetailToOutboxWithoutFeign() throws Exception {
        apply(KEY_1, true, null);
        apply(KEY_2, false, "MEMBER_NOT_FOUND");
        String originalEventId = eventIdsFor(KEY_2).get(0);

        mockMvc.perform(post("/api/v1/admin/markets/{marketId}/refunds/retry", MARKET_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.refundTargetCount").value(1))
                .andExpect(jsonPath("$.data.refundStatus").value("IN_PROGRESS"));

        verifyNoInteractions(memberPointClient);
        assertThat(detailStatuses()).containsExactly("SUCCESS", "PENDING");
        assertThat(predictionStatuses()).containsExactly("REFUNDED", "REFUND_PENDING");
        List<String> eventIds = eventIdsFor(KEY_2);
        assertThat(eventIds).hasSize(2);
        assertThat(eventIds.get(1)).isNotEqualTo(originalEventId);
        assertThat(eventIdsFor(KEY_1)).hasSize(1);

        String retryPayload = jdbcTemplate.queryForObject(
                "SELECT payload FROM market_outbox_event WHERE event_id = ?", String.class, eventIds.get(1));
        assertThat(objectMapper.readTree(retryPayload).get("data").get("reason").asText())
                .isEqualTo("Market 무효 처리 환불 재시도");

        assertThat(apply(KEY_2, true, null)).isEqualTo(PointResultApplyOutcome.APPLIED);
        assertThat(voidRefundStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void manualRetryRejectsWhileRequestsAreStillInFlight() throws Exception {
        mockMvc.perform(post("/api/v1/admin/markets/{marketId}/refunds/retry", MARKET_ID))
                .andExpect(jsonPath("$.errorCode").value("MARKET_REFUND_NOT_ALLOWED"));

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM market_outbox_event", Integer.class))
                .isEqualTo(2);
        verifyNoInteractions(memberPointClient);
    }

    private PointResultApplyOutcome apply(String key, boolean credited, String errorCode) {
        return refundTransactionService.applyAsyncRefundResult(MARKET_ID, key, credited, errorCode);
    }

    private List<String> detailStatuses() {
        return jdbcTemplate.queryForList(
                "SELECT status FROM market_refund_detail ORDER BY prediction_id", String.class);
    }

    private List<String> predictionStatuses() {
        return jdbcTemplate.queryForList(
                "SELECT status FROM market_prediction ORDER BY id", String.class);
    }

    private String voidRefundStatus() {
        return jdbcTemplate.queryForObject(
                "SELECT refund_status FROM market_void WHERE id = ?", String.class, VOID_ID);
    }

    private List<String> eventIdsFor(String idempotencyKey) {
        return jdbcTemplate.queryForList(
                "SELECT event_id FROM market_outbox_event WHERE payload LIKE ? ORDER BY id",
                String.class, "%" + idempotencyKey + "%");
    }

    private void insertVoidedMarketWithVoid() {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                INSERT INTO market (
                    id, title, category, answer_type, judge_data_source, judge_criteria, judge_date,
                    status, close_at, total_pool, initial_virtual_liquidity, created_by, created_at, updated_at
                ) VALUES (?, 'Async Refund Result Market', 'PRICE_INDEX', 'YES_NO', 'TEST', 'TEST',
                          ?, 'VOIDED', ?, 0.00, 200.00, 1, ?, ?)
                """,
                MARKET_ID, LocalDate.now(), now.minusDays(1), now, now
        );
        jdbcTemplate.update("""
                INSERT INTO market_void (
                    id, market_id, reason_type, reason_detail, refund_status,
                    voided_at, created_at, updated_at
                ) VALUES (?, ?, 'DATA_UNAVAILABLE', '공공 데이터 제공 중단', 'PENDING', ?, ?, ?)
                """,
                VOID_ID, MARKET_ID, now, now, now
        );
    }

    private void insertOption() {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                INSERT INTO market_option (
                    id, market_id, option_code, option_text, display_order,
                    virtual_pool_amount, real_pool_amount, total_contract_quantity,
                    current_price, prediction_count, is_result, created_at, updated_at
                ) VALUES (?, ?, 'YES', '예', 1, 100.00, 0.00, 0.00000000, 0.50000000, 0, FALSE, ?, ?)
                """,
                OPTION_ID, MARKET_ID, now, now
        );
    }

    private void insertPrediction(long predictionId, long memberId, String pointAmount) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                INSERT INTO market_prediction (
                    id, market_id, option_id, member_id, point_amount, price_snapshot, contract_quantity,
                    status, point_spend_idempotency_key, attempt_no, refund_amount, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 0.50000000, 200.00000000, 'CONFIRMED', ?, 1, NULL, ?, ?)
                """,
                predictionId, MARKET_ID, OPTION_ID, memberId, new BigDecimal(pointAmount),
                "ASYNC_RESULT_TEST_KEY_" + predictionId, now, now
        );
    }
}
