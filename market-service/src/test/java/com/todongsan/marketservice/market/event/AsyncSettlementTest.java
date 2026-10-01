package com.todongsan.marketservice.market.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.todongsan.marketservice.market.client.MemberPointClient;
import com.todongsan.marketservice.market.outbox.EventContract;
import com.todongsan.marketservice.market.service.MarketSettlementTransactionService;
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

/**
 * async 정산 (ASYNC_POINT_FLOW.md §14). 데이터와 기대 금액은 sync 테스트(AdminMarketSettlementControllerTest)와 같다.
 */
@SpringBootTest(properties = "market.point-transport.mode=async")
class AsyncSettlementTest {

    private static final long MARKET_ID = 100L;
    private static final long WIN_OPTION_ID = 101L;
    private static final long LOSE_OPTION_ID = 102L;
    private static final EventContract CONTRACT = EventContract.load("market.settlement-payout-requests.v1.schema.json");

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MarketSettlementTransactionService settlementTransactionService;

    @MockitoBean
    private MemberPointClient memberPointClient;

    @BeforeEach
    void setUp() {
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
    }

    @Test
    void settleWritesPayoutRequestsSettlesLosersAndCompletesOnResult() throws Exception {
        insertSettlementMarket("5.00");
        insertSettlementOptions();
        insertPrediction(1001L, WIN_OPTION_ID, 1L, "100.00", "200.00000000");
        insertPrediction(1002L, LOSE_OPTION_ID, 2L, "100.00", "200.00000000");

        mockMvc.perform(post("/api/v1/admin/markets/{marketId}/settlements", MARKET_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalPool").value("200.00"))
                .andExpect(jsonPath("$.data.feeAmount").value("5.00"))
                .andExpect(jsonPath("$.data.settlementPool").value("195.00"))
                .andExpect(jsonPath("$.data.payoutPerContract").value("0.47500000"))
                .andExpect(jsonPath("$.data.burnedPointAmount").value("0.00"))
                .andExpect(jsonPath("$.data.winnerCount").value(1))
                .andExpect(jsonPath("$.data.loserCount").value(1))
                .andExpect(jsonPath("$.data.successCount").value(0))
                .andExpect(jsonPath("$.data.marketStatus").value("SETTLEMENT_IN_PROGRESS"))
                .andExpect(jsonPath("$.data.settlementStatus").value("IN_PROGRESS"));

        verifyNoInteractions(memberPointClient);
        // S1: 패자는 준비 트랜잭션에서 바로 처리, 승자는 결과 대기
        assertPrediction(1002L, "SETTLED", "0.00");
        assertPrediction(1001L, "CONFIRMED", null);
        assertThat(detailStatuses()).containsExactly("PENDING");
        assertThat(reputationTaskCount()).isZero();

        List<String> payloads = outboxPayloads();
        assertThat(payloads).hasSize(1);
        assertThat(CONTRACT.validate(payloads.get(0))).isEmpty();
        JsonNode event = objectMapper.readTree(payloads.get(0));
        assertThat(event.get("eventType").asText()).isEqualTo("MARKET_SETTLEMENT_PAYOUT_REQUESTED");
        assertThat(event.get("data").get("amount").asText()).isEqualTo("195.00");
        assertThat(event.get("data").get("reason").asText()).isEqualTo("Market 정산 보상");
        assertThat(event.get("data").get("idempotencyKey").asText())
                .isEqualTo("MARKET_SETTLEMENT_REWARD:market:100:prediction:1001:member:1");
        assertThat(jdbcTemplate.queryForObject("SELECT aggregate_id FROM market_outbox_event", String.class))
                .isEqualTo("1");
        assertThat(jdbcTemplate.queryForObject("SELECT aggregate_type FROM market_outbox_event", String.class))
                .isEqualTo("settlement-payout-requests");

        assertThat(apply(1001L, 1L, true, null)).isEqualTo(PointResultApplyOutcome.APPLIED);

        assertThat(marketStatus()).isEqualTo("SETTLED");
        assertThat(settlementStatus()).isEqualTo("COMPLETED");
        assertPrediction(1001L, "SETTLED", "195.00");
        assertThat(reputationTaskCount()).isEqualTo(2);
    }

    @Test
    void redeliveredResultDoesNotDuplicateReputationTasks() throws Exception {
        insertSettlementMarket("5.00");
        insertSettlementOptions();
        insertPrediction(1001L, WIN_OPTION_ID, 1L, "100.00", "200.00000000");
        insertPrediction(1002L, LOSE_OPTION_ID, 2L, "100.00", "200.00000000");
        settle();
        apply(1001L, 1L, true, null);

        assertThat(apply(1001L, 1L, true, null)).isEqualTo(PointResultApplyOutcome.SKIPPED);
        assertThat(apply(1001L, 1L, false, "IDEMPOTENCY_KEY_CONFLICT")).isEqualTo(PointResultApplyOutcome.SKIPPED);

        assertThat(detailStatuses()).containsExactly("SUCCESS");
        assertThat(marketStatus()).isEqualTo("SETTLED");
        assertThat(reputationTaskCount()).isEqualTo(2);
    }

    @Test
    void payoutAmountsAndBurnedRemainderMatchSyncSettlement() throws Exception {
        insertSettlementMarket("0.00");
        insertSettlementOptions();
        insertPrediction(1001L, WIN_OPTION_ID, 1L, "10.00", "1.00000000");
        insertPrediction(1002L, WIN_OPTION_ID, 2L, "10.00", "1.00000000");
        insertPrediction(1003L, WIN_OPTION_ID, 3L, "10.00", "1.00000000");
        insertPrediction(1004L, LOSE_OPTION_ID, 4L, "10.00", "1.00000000");

        mockMvc.perform(post("/api/v1/admin/markets/{marketId}/settlements", MARKET_ID))
                .andExpect(jsonPath("$.data.totalPool").value("40.00"))
                .andExpect(jsonPath("$.data.payoutPerContract").value("3.33333333"))
                .andExpect(jsonPath("$.data.burnedPointAmount").value("0.01"));

        assertThat(jdbcTemplate.queryForList(
                "SELECT settled_amount FROM market_settlement_detail ORDER BY prediction_id", String.class
        )).containsExactly("13.33", "13.33", "13.33");
        List<String> amounts = outboxPayloads().stream()
                .map(this::readAmount)
                .toList();
        assertThat(amounts).containsExactly("13.33", "13.33", "13.33");
        assertPrediction(1004L, "SETTLED", "0.00");

        apply(1001L, 1L, true, null);
        apply(1002L, 2L, true, null);
        assertThat(marketStatus()).isEqualTo("SETTLEMENT_IN_PROGRESS");
        apply(1003L, 3L, true, null);
        assertThat(marketStatus()).isEqualTo("SETTLED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT burned_point_amount FROM market_settlement WHERE market_id = ?", String.class, MARKET_ID
        )).isEqualTo("0.01");
    }

    @Test
    void failedResultKeepsInProgressAndManualRetryRepublishesWithoutFeign() throws Exception {
        insertSettlementMarket("5.00");
        insertSettlementOptions();
        insertPrediction(1001L, WIN_OPTION_ID, 1L, "100.00", "200.00000000");
        insertPrediction(1003L, WIN_OPTION_ID, 3L, "100.00", "200.00000000");
        insertPrediction(1002L, LOSE_OPTION_ID, 2L, "100.00", "200.00000000");
        settle();
        apply(1001L, 1L, true, null);

        assertThat(apply(1003L, 3L, false, "MEMBER_NOT_FOUND")).isEqualTo(PointResultApplyOutcome.APPLIED);

        assertThat(detailStatuses()).containsExactly("SUCCESS", "FAILED");
        assertPrediction(1003L, "CONFIRMED", null);
        assertThat(marketStatus()).isEqualTo("SETTLEMENT_IN_PROGRESS");
        assertThat(reputationTaskCount()).isZero();

        mockMvc.perform(post("/api/v1/admin/markets/{marketId}/settlements/retry", MARKET_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.marketStatus").value("SETTLEMENT_IN_PROGRESS"))
                .andExpect(jsonPath("$.data.settlementStatus").value("IN_PROGRESS"));

        verifyNoInteractions(memberPointClient);
        assertThat(detailStatuses()).containsExactly("SUCCESS", "PENDING");
        List<String> retryPayloads = outboxPayloads().stream()
                .filter(payload -> payload.contains("prediction:1003"))
                .toList();
        assertThat(retryPayloads).hasSize(2);
        assertThat(objectMapper.readTree(retryPayloads.get(1)).get("data").get("reason").asText())
                .isEqualTo("Market 정산 보상 재시도");

        assertThat(apply(1003L, 3L, true, null)).isEqualTo(PointResultApplyOutcome.APPLIED);
        assertThat(marketStatus()).isEqualTo("SETTLED");
        assertThat(reputationTaskCount()).isEqualTo(3);
    }

    @Test
    void unknownIdempotencyKeyIsReportedAsMissingDetail() {
        assertThat(settlementTransactionService.applyAsyncSettlementResult(
                MARKET_ID, "MARKET_SETTLEMENT_REWARD:market:100:prediction:9999:member:9", true, null
        )).isEqualTo(PointResultApplyOutcome.DETAIL_NOT_FOUND);
    }

    private void settle() throws Exception {
        mockMvc.perform(post("/api/v1/admin/markets/{marketId}/settlements", MARKET_ID))
                .andExpect(jsonPath("$.data.settlementStatus").value("IN_PROGRESS"));
    }

    private PointResultApplyOutcome apply(long predictionId, long memberId, boolean credited, String errorCode) {
        String key = "MARKET_SETTLEMENT_REWARD:market:%d:prediction:%d:member:%d".formatted(MARKET_ID, predictionId, memberId);
        return settlementTransactionService.applyAsyncSettlementResult(MARKET_ID, key, credited, errorCode);
    }

    private String readAmount(String payload) {
        try {
            return objectMapper.readTree(payload).get("data").get("amount").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> outboxPayloads() {
        return jdbcTemplate.queryForList("SELECT payload FROM market_outbox_event ORDER BY id", String.class);
    }

    private List<String> detailStatuses() {
        return jdbcTemplate.queryForList(
                "SELECT status FROM market_settlement_detail ORDER BY prediction_id", String.class);
    }

    private String marketStatus() {
        return jdbcTemplate.queryForObject("SELECT status FROM market WHERE id = ?", String.class, MARKET_ID);
    }

    private String settlementStatus() {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM market_settlement WHERE market_id = ?", String.class, MARKET_ID);
    }

    private int reputationTaskCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM market_reputation_update WHERE market_id = ?", Integer.class, MARKET_ID);
    }

    private void assertPrediction(long predictionId, String status, String settledAmount) {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM market_prediction WHERE id = ?", String.class, predictionId
        )).isEqualTo(status);
        BigDecimal actual = jdbcTemplate.queryForObject(
                "SELECT settled_amount FROM market_prediction WHERE id = ?", BigDecimal.class, predictionId);
        if (settledAmount == null) {
            assertThat(actual).isNull();
        } else {
            assertThat(actual).isEqualByComparingTo(settledAmount);
        }
    }

    private void insertSettlementMarket(String feeRate) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                INSERT INTO market (
                    id, title, category, answer_type, judge_data_source, judge_criteria, judge_date,
                    status, close_at, result_option_id, total_pool, fee_rate, fee_amount,
                    settlement_pool, initial_virtual_liquidity, created_by, created_at, updated_at
                ) VALUES (?, 'Async Settlement Market', 'PRICE_INDEX', 'MULTIPLE_CHOICE', 'TEST', 'TEST',
                          ?, 'CLOSED', ?, ?, 0.00, ?, 0.00, 0.00, 200.00, 1, ?, ?)
                """,
                MARKET_ID, LocalDate.now(), now.minusDays(1), WIN_OPTION_ID, new BigDecimal(feeRate), now, now
        );
    }

    private void insertSettlementOptions() {
        insertSettlementOption(WIN_OPTION_ID, "A", true);
        insertSettlementOption(LOSE_OPTION_ID, "B", false);
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

    private void insertPrediction(long predictionId, long optionId, long memberId, String pointAmount, String quantity) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                INSERT INTO market_prediction (
                    id, market_id, option_id, member_id, point_amount, price_snapshot, contract_quantity,
                    status, point_spend_idempotency_key, attempt_no, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 0.50000000, ?, 'CONFIRMED', ?, 1, ?, ?)
                """,
                predictionId, MARKET_ID, optionId, memberId, new BigDecimal(pointAmount), new BigDecimal(quantity),
                "ASYNC_SETTLEMENT_TEST_KEY_" + predictionId, now, now
        );
    }
}
