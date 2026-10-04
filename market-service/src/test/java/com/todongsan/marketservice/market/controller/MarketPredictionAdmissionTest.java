package com.todongsan.marketservice.market.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.todongsan.marketservice.market.client.MemberPointClient;
import com.todongsan.marketservice.market.client.exception.PointInsufficientException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** 인기 마켓 입장 제한 (docs/architecture/HOT_MARKET_ADMISSION.md §2). 마켓당 동시 2건 */
@SpringBootTest(properties = "market.prediction.admission.max-in-flight-per-market=2")
@AutoConfigureMockMvc
class MarketPredictionAdmissionTest {

    private static final long HOT_MARKET = 1L;
    private static final long OTHER_MARKET = 2L;

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private CircuitBreakerRegistry circuitBreakerRegistry;
    @MockitoBean private MemberPointClient memberPointClient;

    @BeforeEach
    void setUp() {
        reset(memberPointClient);
        circuitBreakerRegistry.circuitBreaker("memberPointSpend").reset();
        for (String table : new String[]{"market_price_history", "market_refund_detail", "market_void",
                "market_prediction", "market_option", "market"}) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        insertMarket(HOT_MARKET, 1L);
        insertMarket(OTHER_MARKET, 3L);
    }

    @Test
    void 한도를_넘으면_즉시_거절하고_다른_마켓과_반환된_자리는_정상이다() throws Exception {
        CountDownLatch inSpend = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            inSpend.countDown();
            release.await(10, TimeUnit.SECONDS);
            return null;
        }).when(memberPointClient).spend(argThat(command -> command != null && command.memberId() <= 2));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<MvcResult>> holders = new ArrayList<>();
        for (long member = 1; member <= 2; member++) {
            long m = member;
            holders.add(pool.submit(() -> predict(HOT_MARKET, m).andReturn()));
        }
        assertThat(inSpend.await(10, TimeUnit.SECONDS)).isTrue();

        // 자리 2개가 모두 진행 중: 같은 마켓 세 번째는 DB·MP를 건드리지 않고 즉시 503
        predict(HOT_MARKET, 3L)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("SERVICE_UNAVAILABLE"));
        verify(memberPointClient, never()).spend(argThat(command -> command != null && command.memberId() == 3L));
        assertThat(predictionCount(HOT_MARKET, 3L)).isZero();

        // 다른 마켓은 영향 없음
        predict(OTHER_MARKET, 3L).andExpect(jsonPath("$.data.status").value("CONFIRMED"));

        release.countDown();
        for (Future<MvcResult> holder : holders) {
            assertThat(holder.get(10, TimeUnit.SECONDS).getResponse().getContentAsString()).contains("CONFIRMED");
        }
        pool.shutdown();

        // 끝난 요청의 자리는 돌아온다
        predict(HOT_MARKET, 4L).andExpect(jsonPath("$.data.status").value("CONFIRMED"));
    }

    @Test
    void 실패한_요청도_자리를_돌려준다() throws Exception {
        doThrow(new PointInsufficientException("POINT_INSUFFICIENT")).when(memberPointClient).spend(any());
        for (long member = 1; member <= 3; member++) {
            predict(HOT_MARKET, member).andExpect(jsonPath("$.errorCode").value("POINT_INSUFFICIENT"));
        }
        // 생성 단계 실패(이미 참여)도 마찬가지
        reset(memberPointClient);
        predict(HOT_MARKET, 4L).andExpect(jsonPath("$.data.status").value("CONFIRMED"));
        for (int i = 0; i < 3; i++) {
            predict(HOT_MARKET, 4L).andExpect(jsonPath("$.errorCode").value("MARKET_ALREADY_PREDICTED"));
        }
        predict(HOT_MARKET, 5L).andExpect(jsonPath("$.data.status").value("CONFIRMED"));
    }

    private ResultActions predict(long marketId, long memberId) throws Exception {
        long optionId = marketId == HOT_MARKET ? 1L : 3L;
        return mockMvc.perform(post("/api/v1/markets/{marketId}/predictions", marketId)
                .header("X-Member-Id", memberId)
                .header("Idempotency-Key", "MARKET_PREDICTION_SPEND:market:%d:member:%d".formatted(marketId, memberId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"marketOptionId": %d, "pointAmount": "100.00"}
                        """.formatted(optionId)));
    }

    private int predictionCount(long marketId, long memberId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM market_prediction WHERE market_id = ? AND member_id = ?",
                Integer.class, marketId, memberId);
    }

    private void insertMarket(long marketId, long firstOptionId) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                INSERT INTO market (
                    id, title, category, answer_type, judge_data_source, judge_criteria, judge_date,
                    status, close_at, total_pool, initial_virtual_liquidity, created_by, created_at, updated_at
                ) VALUES (?, 'Admission Test Market', 'PRICE_INDEX', 'YES_NO', 'TEST', 'TEST',
                          ?, 'ACTIVE', ?, 0.00, 200.00, 1, ?, ?)
                """, marketId, LocalDate.now().plusDays(2), now.plusDays(1), now, now);
        for (int i = 0; i < 2; i++) {
            jdbcTemplate.update("""
                    INSERT INTO market_option (
                        id, market_id, option_code, option_text, display_order,
                        virtual_pool_amount, real_pool_amount, total_contract_quantity,
                        current_price, prediction_count, is_result, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, 100.00, 0.00, 0.00000000, 0.50000000, 0, FALSE, ?, ?)
                    """, firstOptionId + i, marketId, i == 0 ? "A" : "B", i == 0 ? "A" : "B", i + 1, now, now);
        }
    }
}
