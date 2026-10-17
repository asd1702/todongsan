package com.todongsan.marketservice.market.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.todongsan.marketservice.global.exception.CustomException;
import com.todongsan.marketservice.market.dto.response.CreatePredictionResponse;
import com.todongsan.marketservice.market.service.MarketPredictionTransactionService.ConfirmOutcome;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 묶음 확정은 같은 순서의 건별 확정과 결과가 숫자 하나까지 같아야 한다 (PREDICTION_BATCH_CONFIRM.md §4 1번).
 * 같은 조건의 마켓 두 개(건별 100 / 묶음 200)에 같은 확정 순서를 적용해 응답·선택지·마켓 풀·가격 이력·예측 상태를 비교한다.
 */
@SpringBootTest
@ActiveProfiles("mysqlit")
class PredictionBatchConfirmEquivalenceTest {

    private static final long RECORD_MARKET = 100L;
    private static final long BATCH_MARKET = 200L;
    private static final long FOREIGN_MARKET = 300L;

    @Autowired private MarketPredictionTransactionService transactionService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        for (String table : List.of("market_outbox_event", "market_price_history", "market_reputation_update",
                "market_refund_detail", "market_settlement_detail", "market_settlement", "market_void",
                "market_prediction", "market_option", "market")) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        // 순번 5 예측이 참조하는 다른 마켓의 선택지 (외래 키 때문에 먼저 만든다)
        insertMarket(FOREIGN_MARKET);
        insertOption(FOREIGN_MARKET * 10 + 1, FOREIGN_MARKET, "A", 1);
        seed(RECORD_MARKET);
        seed(BATCH_MARKET);
    }

    @Test
    void 묶음_확정은_건별_확정과_결과가_같다() {
        List<String> recordOutcomes = new ArrayList<>();
        for (long index : order()) {
            try {
                recordOutcomes.add(normalize(transactionService.confirmPrediction(RECORD_MARKET * 10 + index)));
            } catch (CustomException e) {
                recordOutcomes.add("ERROR|" + e.getErrorCode().getCode());
            }
        }

        List<ConfirmOutcome> batch = transactionService.confirmPredictionsInBatch(BATCH_MARKET,
                order().stream().map(index -> BATCH_MARKET * 10 + index).toList());
        List<String> batchOutcomes = batch.stream()
                .map(o -> o.failure() == null ? normalize(o.response())
                        : "ERROR|" + ((CustomException) o.failure()).getErrorCode().getCode())
                .toList();

        assertThat(batchOutcomes).containsExactlyElementsOf(recordOutcomes);
        assertThat(batchOutcomes).extracting(s -> s.split("\\|")[0]).containsExactly(
                "CONFIRMED", "CONFIRMED", "ERROR", "CONFIRMED", "ERROR", "CONFIRMED", "ERROR");
        assertThat(state(BATCH_MARKET)).containsExactlyElementsOf(state(RECORD_MARKET));
        // 두 경로가 계산 코드(applyConfirmation)를 같이 쓰므로, 같이 틀리는 경우를 잡으려고 절대값도 본다
        assertThat(jdbcTemplate.queryForObject("SELECT total_pool FROM market WHERE id = ?", String.class, BATCH_MARKET))
                .isEqualTo("248.32"); // 100 + 50.55 + 77.77 + 20
        assertThat(jdbcTemplate.queryForList("SELECT prediction_count FROM market_option WHERE market_id = ? ORDER BY id",
                Integer.class, BATCH_MARKET)).containsExactly(1, 2, 1);
    }

    /**
     * 확정 순서 (예측 순번): 1 정상, 2 정상(소수 금액), 3 이미 확정됨, 4 정상(세 번째 선택지),
     * 5 이 마켓 선택지가 아님(OPTION_NOT_FOUND), 6 정상, 1 다시(이미 확정됨)
     */
    private static List<Long> order() {
        return List.of(1L, 2L, 3L, 4L, 5L, 6L, 1L);
    }

    /** 선택지 3개, 예측 6개. 예측 ID = 마켓*10 + 순번, 선택지 ID = 마켓*10 + 순번 */
    private void seed(long marketId) {
        insertMarket(marketId);
        insertOption(marketId * 10 + 1, marketId, "A", 1);
        insertOption(marketId * 10 + 2, marketId, "B", 2);
        insertOption(marketId * 10 + 3, marketId, "C", 3);
        insertPrediction(marketId, 1, marketId * 10 + 1, "100.00", "POINT_PENDING");
        insertPrediction(marketId, 2, marketId * 10 + 2, "50.55", "POINT_PENDING");
        insertPrediction(marketId, 3, marketId * 10 + 1, "30.00", "CONFIRMED");
        insertPrediction(marketId, 4, marketId * 10 + 3, "77.77", "POINT_PENDING");
        insertPrediction(marketId, 5, FOREIGN_MARKET * 10 + 1, "10.00", "POINT_PENDING");
        insertPrediction(marketId, 6, marketId * 10 + 2, "20.00", "POINT_PENDING");
    }

    private static String normalize(CreatePredictionResponse response) {
        return String.join("|", String.valueOf(response.getStatus()), String.valueOf(response.getPredictionId() % 10),
                String.valueOf(response.getSelectedOptionId() % 10), response.getPointAmount().toPlainString(),
                response.getPriceSnapshot().toPlainString(), response.getContractQuantity().toPlainString());
    }

    /** 마켓마다 다른 ID는 순번으로 바꿔 비교한다. 시각은 비교하지 않는다(건별은 건마다, 묶음은 한 번 now) */
    private List<String> state(long marketId) {
        List<String> rows = new ArrayList<>();
        rows.add("market|" + jdbcTemplate.queryForObject("SELECT total_pool FROM market WHERE id = ?", String.class, marketId));
        jdbcTemplate.queryForList("""
                SELECT id, real_pool_amount, total_contract_quantity, current_price, prediction_count
                FROM market_option WHERE market_id = ? ORDER BY id""", marketId)
                .forEach(r -> rows.add("option|" + ((Number) r.get("ID")).longValue() % 10 + "|" + r.get("REAL_POOL_AMOUNT")
                        + "|" + r.get("TOTAL_CONTRACT_QUANTITY") + "|" + r.get("CURRENT_PRICE") + "|" + r.get("PREDICTION_COUNT")));
        jdbcTemplate.queryForList("""
                SELECT id, status, price_snapshot, contract_quantity
                FROM market_prediction WHERE market_id = ? ORDER BY id""", marketId)
                .forEach(r -> rows.add("prediction|" + ((Number) r.get("ID")).longValue() % 10 + "|" + r.get("STATUS")
                        + "|" + r.get("PRICE_SNAPSHOT") + "|" + r.get("CONTRACT_QUANTITY")));
        jdbcTemplate.queryForList("""
                SELECT prediction_id, option_id, price_before, price_after, real_pool_before, real_pool_after,
                       contract_quantity_before, contract_quantity_after, event_type
                FROM market_price_history WHERE market_id = ? ORDER BY id""", marketId)
                .forEach(r -> rows.add("history|" + ((Number) r.get("PREDICTION_ID")).longValue() % 10
                        + "|" + ((Number) r.get("OPTION_ID")).longValue() % 10 + "|" + r.get("PRICE_BEFORE") + "|" + r.get("PRICE_AFTER")
                        + "|" + r.get("REAL_POOL_BEFORE") + "|" + r.get("REAL_POOL_AFTER") + "|" + r.get("CONTRACT_QUANTITY_BEFORE")
                        + "|" + r.get("CONTRACT_QUANTITY_AFTER") + "|" + r.get("EVENT_TYPE")));
        return rows;
    }

    private void insertMarket(long id) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                INSERT INTO market (id, title, category, answer_type, judge_data_source, judge_criteria, judge_date,
                                    status, close_at, total_pool, initial_virtual_liquidity, created_by, created_at, updated_at)
                VALUES (?, 'Batch Confirm Market', 'PRICE_INDEX', 'MULTIPLE_CHOICE', 'TEST', 'TEST', ?, 'ACTIVE', ?,
                        0.00, 300.00, 1, ?, ?)
                """, id, LocalDate.now().plusDays(2), now.plusDays(1), now, now);
    }

    private void insertOption(long id, long marketId, String code, int order) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                INSERT INTO market_option (id, market_id, option_code, option_text, display_order, virtual_pool_amount,
                                           real_pool_amount, total_contract_quantity, current_price, prediction_count,
                                           is_result, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 100.00, 0.00, 0.00000000, 0.33333333, 0, FALSE, ?, ?)
                """, id, marketId, code, code, order, now, now);
    }

    private void insertPrediction(long marketId, int index, long optionId, String amount, String status) {
        LocalDateTime now = LocalDateTime.now();
        long id = marketId * 10 + index;
        jdbcTemplate.update("""
                INSERT INTO market_prediction (id, market_id, option_id, member_id, point_amount, price_snapshot,
                                               contract_quantity, status, point_spend_idempotency_key, attempt_no,
                                               created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 0.33333333, 300.00000000, ?, ?, 1, ?, ?)
                """, id, marketId, optionId, index, amount, status, "BATCH_EQ_KEY_" + id, now, now);
    }
}
