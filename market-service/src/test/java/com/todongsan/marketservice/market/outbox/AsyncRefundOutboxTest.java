package com.todongsan.marketservice.market.outbox;

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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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

@SpringBootTest(properties = "market.point-transport.mode=async")
class AsyncRefundOutboxTest {

    private static final long MARKET_ID = 100L;
    private static final long OPTION_ID = 101L;
    private static final long VOID_ID = 500L;
    private static final EventContract CONTRACT = EventContract.load("market.point-requests.v1.schema.json");

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

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
        jdbcTemplate.update("DELETE FROM market_refund_detail");
        jdbcTemplate.update("DELETE FROM market_settlement_detail");
        jdbcTemplate.update("DELETE FROM market_settlement");
        jdbcTemplate.update("DELETE FROM market_void");
        jdbcTemplate.update("DELETE FROM market_prediction");
        jdbcTemplate.update("DELETE FROM market_option");
        jdbcTemplate.update("DELETE FROM market");
    }

    @Test
    void refundMarketWritesOneOutboxEventPerDetailWithoutCallingMemberPoint() throws Exception {
        insertVoidedMarketWithVoid();
        insertOption();
        insertPrediction(1001L, 1L, "100.00");
        insertPrediction(1002L, 2L, "50.50");

        mockMvc.perform(post("/api/v1/admin/markets/{marketId}/refunds", MARKET_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.refundTargetCount").value(2))
                .andExpect(jsonPath("$.data.successCount").value(0))
                .andExpect(jsonPath("$.data.failedCount").value(0))
                .andExpect(jsonPath("$.data.unknownCount").value(0))
                .andExpect(jsonPath("$.data.refundStatus").value("IN_PROGRESS"));

        verifyNoInteractions(memberPointClient);
        assertThat(jdbcTemplate.queryForObject("SELECT refund_status FROM market_void WHERE id = ?", String.class, VOID_ID))
                .isEqualTo("IN_PROGRESS");
        assertThat(jdbcTemplate.queryForList(
                "SELECT status FROM market_refund_detail ORDER BY prediction_id",
                String.class
        )).containsExactly("PENDING", "PENDING");

        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT event_id, aggregate_type, aggregate_id, event_type, payload
                FROM market_outbox_event
                ORDER BY aggregate_id
                """);
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(row -> row.get("AGGREGATE_TYPE")).containsOnly("point-requests");
        assertThat(rows).extracting(row -> row.get("EVENT_TYPE")).containsOnly("MARKET_REFUND_REQUESTED");
        // 기본 키 전략: member_id
        assertThat(rows).extracting(row -> row.get("AGGREGATE_ID")).containsExactly("1", "2");

        for (Map<String, Object> row : rows) {
            String payload = (String) row.get("PAYLOAD");
            assertThat(CONTRACT.validate(payload)).isEmpty();
            assertThat(objectMapper.readTree(payload).get("eventId").asText()).isEqualTo(row.get("EVENT_ID"));
        }

        JsonNode first = objectMapper.readTree((String) rows.get(0).get("PAYLOAD"));
        assertThat(first.get("producer").asText()).isEqualTo("market-service");
        assertThat(first.get("schemaVersion").asInt()).isEqualTo(1);
        JsonNode data = first.get("data");
        assertThat(data.get("marketId").asLong()).isEqualTo(MARKET_ID);
        assertThat(data.get("predictionId").asLong()).isEqualTo(1001L);
        assertThat(data.get("memberId").asLong()).isEqualTo(1L);
        assertThat(data.get("amount").asText()).isEqualTo("100.00");
        assertThat(data.get("referenceType").asText()).isEqualTo("MARKET_PREDICTION");
        assertThat(data.get("referenceId").asLong()).isEqualTo(1001L);
        assertThat(data.get("reason").asText()).isEqualTo("Market 무효 처리 환불");
        assertThat(data.get("idempotencyKey").asText())
                .isEqualTo("MARKET_REFUND:market:100:prediction:1001:member:1");
        assertThat(objectMapper.readTree((String) rows.get(1).get("PAYLOAD")).get("data").get("amount").asText())
                .isEqualTo("50.50");
    }

    @Test
    void refundMarketWithoutTargetsCompletesWithoutOutboxEvents() throws Exception {
        insertVoidedMarketWithVoid();
        insertOption();

        mockMvc.perform(post("/api/v1/admin/markets/{marketId}/refunds", MARKET_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.refundStatus").value("COMPLETED"));

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM market_outbox_event", Integer.class)).isZero();
        verifyNoInteractions(memberPointClient);
    }

    private void insertVoidedMarketWithVoid() {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                INSERT INTO market (
                    id, title, category, answer_type, judge_data_source, judge_criteria, judge_date,
                    status, close_at, total_pool, initial_virtual_liquidity, created_by, created_at, updated_at
                ) VALUES (?, 'Async Refund Market', 'PRICE_INDEX', 'YES_NO', 'TEST', 'TEST',
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
                "ASYNC_REFUND_TEST_KEY_" + predictionId, now, now
        );
    }
}
