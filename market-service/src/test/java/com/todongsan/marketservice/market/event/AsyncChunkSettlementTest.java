package com.todongsan.marketservice.market.event;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * 정산 지급 요청 묶음(v2) 발행 (ASYNC_POINT_FLOW.md §18). 정산 준비와 R5 재시도가 같은 묶음 발행을 쓴다.
 * 금액 계산은 AsyncSettlementTest(payoutAmountsAndBurnedRemainderMatchSyncSettlement)와 같은 데이터다.
 */
@SpringBootTest(properties = {
        "market.point-transport.mode=async",
        "market.point-transport.settlement-request-format=chunk",
        "market.point-transport.settlement-request-chunk-size=2"
})
class AsyncChunkSettlementTest {

    private static final long MARKET_ID = 100L;
    private static final long WIN_OPTION_ID = 101L;
    private static final long LOSE_OPTION_ID = 102L;
    private static final EventContract CONTRACT = EventContract.load("market.settlement-payout-requests.v2.schema.json");

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
        for (String table : List.of("market_outbox_event", "market_price_history", "market_reputation_update",
                "market_refund_detail", "market_settlement_detail", "market_settlement", "market_void",
                "market_prediction", "market_option", "market")) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        insertMarket();
        insertOption(WIN_OPTION_ID, "A", true);
        insertOption(LOSE_OPTION_ID, "B", false);
        // 회원 순서와 예측 순서를 다르게 둔다: 묶음은 회원 순 (3 → 1, 2 → 2, 1 → 3)
        insertPrediction(1001L, WIN_OPTION_ID, 3L);
        insertPrediction(1002L, WIN_OPTION_ID, 2L);
        insertPrediction(1003L, WIN_OPTION_ID, 1L);
        insertPrediction(1004L, LOSE_OPTION_ID, 4L);
    }

    @Test
    void settleWritesChunkedPayoutRequestsSortedByMember() throws Exception {
        mockMvc.perform(post("/api/v1/admin/markets/{marketId}/settlements", MARKET_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.winnerCount").value(3))
                .andExpect(jsonPath("$.data.burnedPointAmount").value("0.01"));

        verifyNoInteractions(memberPointClient);
        List<Map<String, Object>> rows = outboxRows();
        assertThat(rows).extracting(row -> row.get("AGGREGATE_ID")).containsExactly("100:0", "100:1");
        assertThat(rows).extracting(row -> row.get("AGGREGATE_TYPE")).containsOnly("settlement-payout-requests");

        List<String> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String payload = (String) row.get("PAYLOAD");
            assertThat(CONTRACT.validate(payload)).isEmpty();
            JsonNode data = objectMapper.readTree(payload).get("data");
            assertThat(data.get("reason").asText()).isEqualTo("Market 정산 보상");
            data.get("items").forEach(item -> items.add(item.get("memberId").asLong() + "|"
                    + item.get("predictionId").asLong() + "|" + item.get("amount").asText() + "|"
                    + item.get("idempotencyKey").asText()));
        }
        assertThat(items).containsExactly(
                "1|1003|13.33|MARKET_SETTLEMENT_REWARD:market:100:prediction:1003:member:1",
                "2|1002|13.33|MARKET_SETTLEMENT_REWARD:market:100:prediction:1002:member:2",
                "3|1001|13.33|MARKET_SETTLEMENT_REWARD:market:100:prediction:1001:member:3");
    }

    @Test
    void manualRetryRepublishesOnlyRetryTargetsAsChunk() throws Exception {
        mockMvc.perform(post("/api/v1/admin/markets/{marketId}/settlements", MARKET_ID));
        apply(1001L, 3L, true, null);
        apply(1002L, 2L, false, "MEMBER_NOT_FOUND");
        apply(1003L, 1L, true, null);

        mockMvc.perform(post("/api/v1/admin/markets/{marketId}/settlements/retry", MARKET_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.settlementStatus").value("IN_PROGRESS"));

        verifyNoInteractions(memberPointClient);
        List<Map<String, Object>> rows = outboxRows();
        assertThat(rows).hasSize(3);
        String retry = (String) rows.get(2).get("PAYLOAD");
        assertThat(CONTRACT.validate(retry)).isEmpty();
        JsonNode data = objectMapper.readTree(retry).get("data");
        assertThat(data.get("reason").asText()).isEqualTo("Market 정산 보상 재시도");
        assertThat(data.get("items")).hasSize(1);
        assertThat(data.get("items").get(0).get("idempotencyKey").asText())
                .isEqualTo("MARKET_SETTLEMENT_REWARD:market:100:prediction:1002:member:2");
        assertThat(rows.get(2).get("AGGREGATE_ID")).isEqualTo("100:0");
    }

    private void apply(long predictionId, long memberId, boolean credited, String errorCode) {
        String key = "MARKET_SETTLEMENT_REWARD:market:%d:prediction:%d:member:%d".formatted(MARKET_ID, predictionId, memberId);
        settlementTransactionService.applyAsyncSettlementResult(MARKET_ID, key, credited, errorCode);
    }

    private List<Map<String, Object>> outboxRows() {
        return jdbcTemplate.queryForList("SELECT aggregate_id, aggregate_type, payload FROM market_outbox_event ORDER BY id");
    }

    private void insertMarket() {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                INSERT INTO market (
                    id, title, category, answer_type, judge_data_source, judge_criteria, judge_date,
                    status, close_at, result_option_id, total_pool, fee_rate, fee_amount,
                    settlement_pool, initial_virtual_liquidity, created_by, created_at, updated_at
                ) VALUES (?, 'Async Chunk Settlement Market', 'PRICE_INDEX', 'MULTIPLE_CHOICE', 'TEST', 'TEST',
                          ?, 'CLOSED', ?, ?, 0.00, 0.00, 0.00, 0.00, 200.00, 1, ?, ?)
                """,
                MARKET_ID, LocalDate.now(), now.minusDays(1), WIN_OPTION_ID, now, now
        );
    }

    private void insertOption(long optionId, String optionCode, boolean isResult) {
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
                ) VALUES (?, ?, ?, ?, ?, 0.50000000, ?, 'CONFIRMED', ?, 1, ?, ?)
                """,
                predictionId, MARKET_ID, optionId, memberId, new BigDecimal("10.00"), new BigDecimal("1.00000000"),
                "ASYNC_CHUNK_TEST_KEY_" + predictionId, now, now
        );
    }
}
