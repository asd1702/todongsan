package com.todongsan.marketservice.market.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.todongsan.marketservice.market.client.MemberPointClient;
import com.todongsan.marketservice.market.service.MarketSettlementTransactionService;
import com.todongsan.marketservice.market.service.PointResultApplyOutcome;
import com.todongsan.marketservice.market.service.PointResultBatchApplier;
import com.todongsan.marketservice.market.service.PointResultBatchApplier.Item;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
 * 정산 결과의 묶음 반영은 건 단위 반영과 결과가 같아야 한다 (ASYNC_POINT_FLOW.md §17.6).
 * 같은 조건의 정산 마켓 두 개에 같은 결과 순서를 각각 건 단위(마켓 300)와 배치(마켓 400)로 반영해 비교한다.
 */
@SpringBootTest(properties = "market.point-transport.mode=async")
class PointResultBatchEquivalenceTest {

    private static final String SETTLEMENT = PointResultBatchApplier.MARKET_SETTLEMENT_PAYOUT_REQUESTED;
    private static final long RECORD_MARKET = 300L;
    private static final long BATCH_MARKET = 400L;

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PointResultBatchApplier batchApplier;

    @Autowired
    private MarketSettlementTransactionService settlementTransactionService;

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
        seedSettlement(RECORD_MARKET);
        seedSettlement(BATCH_MARKET);
    }

    @Test
    void 정산_결과의_묶음_반영은_건_단위_반영과_같다() {
        List<PointResultApplyOutcome> recordOutcomes = new ArrayList<>();
        for (Item item : scenario(RECORD_MARKET)) {
            recordOutcomes.add(settlementTransactionService.applyAsyncSettlementResult(
                    item.marketId(), item.idempotencyKey(), item.credited(), item.errorCode()));
        }
        List<PointResultApplyOutcome> batchOutcomes = batchApplier.applyBatch(scenario(BATCH_MARKET));

        assertThat(batchOutcomes).containsExactlyElementsOf(recordOutcomes);
        assertThat(batchOutcomes).containsExactly(
                PointResultApplyOutcome.APPLIED,           // 1 성공
                PointResultApplyOutcome.APPLIED,           // 2 실패
                PointResultApplyOutcome.APPLIED,           // 2 실패 → 성공
                PointResultApplyOutcome.APPLIED,           // 3 성공
                PointResultApplyOutcome.SKIPPED,           // 3 성공 뒤 늦은 실패
                PointResultApplyOutcome.SKIPPED,           // 1 중복 성공
                PointResultApplyOutcome.DETAIL_NOT_FOUND,  // 없는 키
                PointResultApplyOutcome.APPLIED            // 4 성공 → 정산 완료
        );
        assertThat(state(BATCH_MARKET)).containsExactlyElementsOf(state(RECORD_MARKET));
        assertThat(queryString("SELECT status FROM market WHERE id = " + BATCH_MARKET)).isEqualTo("SETTLED");
    }

    /** 승자 4명(예측 m*10+1..4)과 패자 1명. 정산 시작 API로 detail을 PENDING으로 만든다. */
    private void seedSettlement(long marketId) throws Exception {
        long winOption = marketId + 1;
        long loseOption = marketId + 2;
        insertMarket(marketId, winOption);
        insertOption(winOption, marketId, "A", true);
        insertOption(loseOption, marketId, "B", false);
        for (int i = 1; i <= 4; i++) {
            insertPrediction(marketId * 10 + i, marketId, winOption, i);
        }
        insertPrediction(marketId * 10 + 5, marketId, loseOption, 5);
        mockMvc.perform(post("/api/v1/admin/markets/{id}/settlements", marketId))
                .andExpect(jsonPath("$.data.settlementStatus").value("IN_PROGRESS"));
    }

    private static List<Item> scenario(long marketId) {
        return List.of(
                credited(marketId, 1),
                failed(marketId, 2),
                credited(marketId, 2),
                credited(marketId, 3),
                failed(marketId, 3),
                credited(marketId, 1),
                new Item(SETTLEMENT, marketId, key(marketId, 9), true, null),
                credited(marketId, 4)
        );
    }

    private static Item credited(long marketId, int winner) {
        return new Item(SETTLEMENT, marketId, key(marketId, winner), true, null);
    }

    private static Item failed(long marketId, int winner) {
        return new Item(SETTLEMENT, marketId, key(marketId, winner), false, "MEMBER_NOT_FOUND");
    }

    private static String key(long marketId, int winner) {
        return "MARKET_SETTLEMENT_REWARD:market:%d:prediction:%d:member:%d".formatted(marketId, marketId * 10 + winner, winner);
    }

    /** 마켓마다 다른 ID는 예측 순번(1~5)으로 바꿔 비교한다. */
    private List<String> state(long marketId) {
        List<String> rows = new ArrayList<>();
        jdbcTemplate.queryForList("""
                SELECT d.prediction_id, d.status, d.fail_reason, d.settled_amount
                FROM market_settlement_detail d JOIN market_settlement s ON s.id = d.settlement_id
                WHERE s.market_id = ? ORDER BY d.prediction_id
                """, marketId).forEach(row -> rows.add("detail|" + ((Number) row.get("PREDICTION_ID")).longValue() % 10
                + "|" + row.get("STATUS") + "|" + row.get("FAIL_REASON") + "|" + row.get("SETTLED_AMOUNT")));
        jdbcTemplate.queryForList(
                "SELECT id, status, settled_amount FROM market_prediction WHERE market_id = ? ORDER BY id", marketId)
                .forEach(row -> rows.add("prediction|" + ((Number) row.get("ID")).longValue() % 10
                        + "|" + row.get("STATUS") + "|" + row.get("SETTLED_AMOUNT")));
        rows.add("market|" + queryString("SELECT status FROM market WHERE id = " + marketId));
        rows.add("settlement|" + queryString("SELECT status FROM market_settlement WHERE market_id = " + marketId));
        rows.add("reputation|" + jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM market_reputation_update WHERE market_id = ?", Integer.class, marketId));
        return rows;
    }

    private String queryString(String sql) {
        return jdbcTemplate.queryForObject(sql, String.class);
    }

    private void insertMarket(long id, Long resultOptionId) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                INSERT INTO market (id, title, category, answer_type, judge_data_source, judge_criteria, judge_date,
                                    status, close_at, result_option_id, total_pool, fee_rate, fee_amount, settlement_pool,
                                    initial_virtual_liquidity, created_by, created_at, updated_at)
                VALUES (?, 'Equivalence Test Market', 'PRICE_INDEX', 'MULTIPLE_CHOICE', 'TEST', 'TEST', ?, 'CLOSED', ?, ?,
                        0.00, 5.00, 0.00, 0.00, 200.00, 1, ?, ?)
                """, id, LocalDate.now(), now.minusDays(1), resultOptionId, now, now);
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
                """, id, marketId, optionId, memberId, new BigDecimal("100.00"), "EQ_TEST_KEY_" + id, now, now);
    }
}
