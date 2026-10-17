package com.todongsan.marketservice.market.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.todongsan.marketservice.market.client.MemberPointClient;
import com.todongsan.marketservice.market.client.PointSpendCommand;
import com.todongsan.marketservice.market.client.exception.MemberPointRejectedException;
import com.todongsan.marketservice.market.client.exception.MemberPointTimeoutException;
import com.todongsan.marketservice.market.client.exception.MemberPointUnavailableException;
import com.todongsan.marketservice.market.client.exception.PointInsufficientException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 예측 참여 차감 서킷 브레이커 (docs/architecture/SPEND_CIRCUIT_BREAKER.md §2).
 * 판정을 빨리 보려고 창을 4건으로 줄인다. 판정 기준(실패·느린 호출 50%)은 운영 설정과 같다.
 */
@SpringBootTest(properties = {
        "resilience4j.circuitbreaker.instances.memberPointSpend.sliding-window-type=COUNT_BASED",
        "resilience4j.circuitbreaker.instances.memberPointSpend.sliding-window-size=4",
        "resilience4j.circuitbreaker.instances.memberPointSpend.minimum-number-of-calls=4",
        "resilience4j.circuitbreaker.instances.memberPointSpend.slow-call-duration-threshold=200ms",
        "resilience4j.circuitbreaker.instances.memberPointSpend.wait-duration-in-open-state=60s",
        "resilience4j.circuitbreaker.instances.memberPointSpend.permitted-number-of-calls-in-half-open-state=2"
})
@AutoConfigureMockMvc
class MarketPredictionSpendCircuitBreakerTest {

    private static final long MARKET_ID = 1L;
    private static final long CLOSED_MARKET_ID = 2L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @MockitoBean
    private MemberPointClient memberPointClient;

    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        reset(memberPointClient);
        circuitBreaker = circuitBreakerRegistry.circuitBreaker("memberPointSpend");
        circuitBreaker.reset();
        for (String table : new String[]{"market_price_history", "market_refund_detail", "market_void",
                "market_prediction", "market_option", "market"}) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        insertMarket(MARKET_ID, "ACTIVE");
        insertOption(1L, MARKET_ID, "A", 1);
        insertOption(2L, MARKET_ID, "B", 2);
        insertMarket(CLOSED_MARKET_ID, "CLOSED");
        insertOption(3L, CLOSED_MARKET_ID, "A", 1);
        insertOption(4L, CLOSED_MARKET_ID, "B", 2);
    }

    @Test
    void openCircuitRejectsBeforeCreatingPredictionOrCallingMemberPoint() throws Exception {
        circuitBreaker.transitionToOpenState();

        predict(MARKET_ID, 10L)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("EXTERNAL_SERVICE_UNAVAILABLE"));

        verify(memberPointClient, never()).spend(any());
        assertThat(predictionCount()).isZero();
    }

    @Test
    void timeoutsOpenCircuitAndLaterRequestsAreRejectedWithoutDbWork() throws Exception {
        doThrow(new MemberPointTimeoutException("MEMBER_POINT_TIMEOUT")).when(memberPointClient).spend(any());

        for (long member = 1; member <= 4; member++) {
            predict(MARKET_ID, member).andExpect(jsonPath("$.data.status").value("POINT_UNKNOWN"));
        }
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        predict(MARKET_ID, 5L).andExpect(status().isServiceUnavailable());
        verify(memberPointClient, times(4)).spend(any());
        assertThat(predictionCount()).isEqualTo(4);
    }

    @Test
    void unavailableResponsesCountAsFailures() throws Exception {
        doThrow(new MemberPointUnavailableException("MEMBER_POINT_UNAVAILABLE")).when(memberPointClient).spend(any());

        for (long member = 1; member <= 4; member++) {
            predict(MARKET_ID, member);
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    /** 잔액 부족은 MP가 정상 응답한 것이다. 많아도 차단하지 않는다 (B4) */
    @Test
    void pointInsufficientDoesNotOpenCircuit() throws Exception {
        doThrow(new PointInsufficientException("POINT_INSUFFICIENT")).when(memberPointClient).spend(any());

        for (long member = 1; member <= 6; member++) {
            predict(MARKET_ID, member).andExpect(jsonPath("$.errorCode").value("POINT_INSUFFICIENT"));
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        verify(memberPointClient, times(6)).spend(any());
    }

    /** MP의 4xx 업무 오류(키 충돌 등)는 MP가 정상 응답한 것이다. 데이터 문제 하나로 전체 예측이 막히면 안 된다 (B4 정정) */
    @Test
    void businessRejectionsDoNotOpenCircuit() throws Exception {
        doThrow(new MemberPointRejectedException("IDEMPOTENCY_KEY_CONFLICT")).when(memberPointClient).spend(any());

        for (long member = 1; member <= 6; member++) {
            predict(MARKET_ID, member).andExpect(jsonPath("$.data.status").value("POINT_UNKNOWN"));
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        verify(memberPointClient, times(6)).spend(any());
    }

    @Test
    void slowSuccessfulCallsOpenCircuit() throws Exception {
        doAnswer(invocation -> {
            Thread.sleep(250);
            return null;
        }).when(memberPointClient).spend(any(PointSpendCommand.class));

        for (long member = 1; member <= 4; member++) {
            predict(MARKET_ID, member).andExpect(jsonPath("$.data.status").value("CONFIRMED"));
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    /** 허가를 받은 뒤 예측 생성이 실패하면 허가를 돌려준다. HALF_OPEN 시험 자리(2)를 잡아 두면 정상 요청이 거절된다 */
    @Test
    void permitIsReturnedWhenPredictionCreationFails() throws Exception {
        circuitBreaker.transitionToOpenState();
        circuitBreaker.transitionToHalfOpenState();

        predict(CLOSED_MARKET_ID, 1L).andExpect(jsonPath("$.errorCode").value("MARKET_NOT_ACTIVE"));
        predict(CLOSED_MARKET_ID, 2L).andExpect(jsonPath("$.errorCode").value("MARKET_NOT_ACTIVE"));

        predict(MARKET_ID, 3L).andExpect(jsonPath("$.data.status").value("CONFIRMED"));
        verify(memberPointClient, times(1)).spend(any());
    }

    @Test
    void successfulTrialCallsCloseCircuit() throws Exception {
        circuitBreaker.transitionToOpenState();
        circuitBreaker.transitionToHalfOpenState();

        predict(MARKET_ID, 1L).andExpect(jsonPath("$.data.status").value("CONFIRMED"));
        predict(MARKET_ID, 2L).andExpect(jsonPath("$.data.status").value("CONFIRMED"));

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    private ResultActions predict(long marketId, long memberId) throws Exception {
        long optionId = marketId == MARKET_ID ? 1L : 3L;
        return mockMvc.perform(post("/api/v1/markets/{marketId}/predictions", marketId)
                .header("X-Member-Id", memberId)
                .header("Idempotency-Key", "MARKET_PREDICTION_SPEND:market:%d:member:%d".formatted(marketId, memberId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"marketOptionId": %d, "pointAmount": "100.00"}
                        """.formatted(optionId)));
    }

    private int predictionCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM market_prediction", Integer.class);
    }

    private void insertMarket(long marketId, String status) {
        jdbcTemplate.update("""
                INSERT INTO market (
                    id, title, category, answer_type, judge_data_source, judge_criteria, judge_date,
                    status, close_at, total_pool, initial_virtual_liquidity, created_by, created_at, updated_at
                ) VALUES (?, 'Circuit Breaker Test Market', 'PRICE_INDEX', 'YES_NO', 'TEST', 'TEST',
                          ?, ?, ?, 0.00, 200.00, 1, ?, ?)
                """,
                marketId, LocalDate.now().plusDays(2), status, LocalDateTime.now().plusDays(1),
                LocalDateTime.now(), LocalDateTime.now());
    }

    private void insertOption(long optionId, long marketId, String optionCode, int displayOrder) {
        jdbcTemplate.update("""
                INSERT INTO market_option (
                    id, market_id, option_code, option_text, display_order,
                    virtual_pool_amount, real_pool_amount, total_contract_quantity,
                    current_price, prediction_count, is_result, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 100.00, 0.00, 0.00000000, 0.50000000, 0, FALSE, ?, ?)
                """,
                optionId, marketId, optionCode, optionCode, displayOrder, LocalDateTime.now(), LocalDateTime.now());
    }
}
