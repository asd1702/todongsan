package com.todongsan.memberpointservice.point.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.todongsan.memberpointservice.global.exception.CustomException;
import com.todongsan.memberpointservice.global.exception.ErrorCode;
import com.todongsan.memberpointservice.member.entity.Member;
import com.todongsan.memberpointservice.member.repository.MemberRepository;
import com.todongsan.memberpointservice.point.dto.request.SpendRequest;
import com.todongsan.memberpointservice.point.dto.response.TransactionResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 예측 차감 펜싱 (MEMBER_POINT_API_SPEC.md 3-5, ASYNC_POINT_FLOW.md §12.6). */
@SpringBootTest
class PointSpendFenceTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000);

    @Autowired PointInternalService service;
    @Autowired MemberRepository memberRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper objectMapper;

    @Test
    void 기록이_없으면_잔액_변경_없이_FAILED로_펜싱한다() {
        long memberId = newMember("1000.00");
        String key = key(memberId);

        TransactionResponse response = service.fenceSpend(key, spendRequest(memberId, "300"));

        assertThat(response.getStatus()).isEqualTo("FAILED");
        assertThat(response.getFailReason()).isEqualTo("POINT_SPEND_FENCED");
        assertThat(response.getBalanceSnapshot()).isEqualTo("1000.00");
        assertThat(balance(memberId)).isEqualByComparingTo("1000.00");
        assertThat(historyCount(key)).isEqualTo(1);
        assertThat(service.getTransaction(key).getStatus()).isEqualTo("FAILED");
    }

    @Test
    void 펜싱_뒤에_늦게_도착한_spend는_차감하지_않고_POINT_SPEND_FENCED로_거절한다() {
        long memberId = newMember("1000.00");
        String key = key(memberId);
        service.fenceSpend(key, spendRequest(memberId, "300"));

        assertThatThrownBy(() -> service.spend(key, spendRequest(memberId, "300")))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.POINT_SPEND_FENCED));

        assertThat(balance(memberId)).isEqualByComparingTo("1000.00");
        assertThat(historyCount(key)).isEqualTo(1);
    }

    @Test
    void spend가_먼저_처리됐으면_변경_없이_PROCESSED를_반환한다() {
        long memberId = newMember("1000.00");
        String key = key(memberId);
        service.spend(key, spendRequest(memberId, "300"));

        TransactionResponse response = service.fenceSpend(key, spendRequest(memberId, "300.00"));

        assertThat(response.getStatus()).isEqualTo("PROCESSED");
        assertThat(balance(memberId)).isEqualByComparingTo("700.00");
        assertThat(historyCount(key)).isEqualTo(1);
    }

    @Test
    void 잔액_부족으로_실패한_기록은_그대로_FAILED를_반환한다() {
        long memberId = newMember("100.00");
        String key = key(memberId);
        assertThatThrownBy(() -> service.spend(key, spendRequest(memberId, "300")))
                .isInstanceOf(CustomException.class);

        TransactionResponse response = service.fenceSpend(key, spendRequest(memberId, "300"));

        assertThat(response.getStatus()).isEqualTo("FAILED");
        assertThat(response.getFailReason()).isEqualTo("POINT_INSUFFICIENT");
        assertThat(historyCount(key)).isEqualTo(1);
    }

    @Test
    void 같은_키를_다시_펜싱해도_기록은_하나다() {
        long memberId = newMember("1000.00");
        String key = key(memberId);

        service.fenceSpend(key, spendRequest(memberId, "300"));
        TransactionResponse again = service.fenceSpend(key, spendRequest(memberId, "300"));

        assertThat(again.getFailReason()).isEqualTo("POINT_SPEND_FENCED");
        assertThat(historyCount(key)).isEqualTo(1);
    }

    @Test
    void 기존_기록과_요청_내용이_다르면_IDEMPOTENCY_KEY_CONFLICT() {
        long memberId = newMember("1000.00");
        String key = key(memberId);
        service.spend(key, spendRequest(memberId, "300"));

        assertThatThrownBy(() -> service.fenceSpend(key, spendRequest(memberId, "500")))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_CONFLICT));
    }

    @Test
    void 회원이_없으면_MEMBER_NOT_FOUND() {
        long missingMemberId = 900_000_000L + SEQ.incrementAndGet();

        assertThatThrownBy(() -> service.fenceSpend(key(missingMemberId), spendRequest(missingMemberId, "300")))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MEMBER_NOT_FOUND));
        assertThat(historyCount(key(missingMemberId))).isZero();
    }

    @Test
    void Idempotency_Key가_없으면_IDEMPOTENCY_KEY_REQUIRED() {
        assertThatThrownBy(() -> service.fenceSpend(" ", spendRequest(1L, "300")))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REQUIRED));
    }

    private long newMember(String balance) {
        long n = SEQ.incrementAndGet();
        Member member = memberRepository.save(Member.builder()
                .nickname("fence-test-" + n)
                .email("fence-test-" + n + "@kakao.com")
                .oauthProvider("KAKAO")
                .oauthId("fence-test-" + n)
                .build());
        jdbcTemplate.update("UPDATE member SET point_balance = ? WHERE id = ?", new BigDecimal(balance), member.getId());
        return member.getId();
    }

    private static String key(long memberId) {
        return "MARKET_PREDICTION_SPEND:market:7:member:%d:attempt:1".formatted(memberId);
    }

    private SpendRequest spendRequest(long memberId, String amount) {
        return objectMapper.convertValue(Map.of(
                "memberId", memberId,
                "type", "SPEND_MARKET",
                "amount", new BigDecimal(amount),
                "referenceType", "MARKET_PREDICTION",
                "referenceId", 1001L,
                "reason", "Market 예측 참여"
        ), SpendRequest.class);
    }

    private BigDecimal balance(long memberId) {
        return jdbcTemplate.queryForObject("SELECT point_balance FROM member WHERE id = ?", BigDecimal.class, memberId);
    }

    private int historyCount(String key) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM point_history WHERE idempotency_key = ?", Integer.class, key);
    }
}
