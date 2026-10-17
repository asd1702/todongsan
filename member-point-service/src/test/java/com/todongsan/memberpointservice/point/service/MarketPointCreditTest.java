package com.todongsan.memberpointservice.point.service;

import com.todongsan.memberpointservice.global.util.RequestHashUtil;
import com.todongsan.memberpointservice.member.entity.Member;
import com.todongsan.memberpointservice.member.repository.MemberRepository;
import com.todongsan.memberpointservice.point.dto.response.BatchItemResult;
import com.todongsan.memberpointservice.point.entity.PointHistory;
import com.todongsan.memberpointservice.point.entity.PointHistoryType;
import com.todongsan.memberpointservice.point.entity.PointReferenceType;
import com.todongsan.memberpointservice.point.entity.PointTransactionStatus;
import com.todongsan.memberpointservice.point.repository.PointHistoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketPointCreditTest {

    @Mock MemberRepository memberRepository;
    @Mock PointHistoryRepository pointHistoryRepository;
    @InjectMocks PointInternalServiceImpl service;

    private static final Long MEMBER_ID = 1001L;
    private static final Long PREDICTION_ID = 7L;
    private static final BigDecimal AMOUNT = new BigDecimal("300.00");
    private static final String KEY = "MARKET_REFUND:market:42:prediction:7:member:1001";

    @Test
    void 환불_적립은_REFUND_MARKET_이력으로_저장한다() {
        when(pointHistoryRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        when(memberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member()));
        when(memberRepository.earnPoint(eq(MEMBER_ID), any())).thenReturn(1);

        BatchItemResult result = credit();

        assertThat(result.getStatus()).isEqualTo("PROCESSED");
        ArgumentCaptor<PointHistory> captor = ArgumentCaptor.forClass(PointHistory.class);
        verify(pointHistoryRepository).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(PointHistoryType.REFUND_MARKET);
        assertThat(captor.getValue().getIdempotencyKey()).isEqualTo(KEY);
        assertThat(captor.getValue().getReferenceType()).isEqualTo(PointReferenceType.MARKET_PREDICTION);
    }

    @Test
    void 같은_이벤트를_다시_받으면_잔액을_바꾸지_않고_ALREADY_PROCESSED를_반환한다() {
        String hash = RequestHashUtil.compute(MEMBER_ID, "REFUND_MARKET", AMOUNT, "MARKET_PREDICTION", PREDICTION_ID);
        when(pointHistoryRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(history(hash)));

        BatchItemResult result = credit();

        assertThat(result.getStatus()).isEqualTo("ALREADY_PROCESSED");
        verify(memberRepository, never()).earnPoint(anyLong(), any());
        verify(pointHistoryRepository, never()).save(any());
    }

    @Test
    void 없는_회원이면_FAILED_MEMBER_NOT_FOUND를_반환한다() {
        when(pointHistoryRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        when(memberRepository.findById(MEMBER_ID)).thenReturn(Optional.empty());

        BatchItemResult result = credit();

        assertThat(result.getStatus()).isEqualTo("FAILED");
        assertThat(result.getErrorCode()).isEqualTo("MEMBER_NOT_FOUND");
        verify(memberRepository, never()).earnPoint(anyLong(), any());
    }

    private BatchItemResult credit() {
        return service.creditMarketPoint(PointHistoryType.REFUND_MARKET, PREDICTION_ID, MEMBER_ID, AMOUNT,
                "MARKET_PREDICTION", PREDICTION_ID, "Market 무효 처리 환불", KEY);
    }

    private Member member() {
        return Member.builder()
                .nickname("테스트유저")
                .email("test@kakao.com")
                .oauthProvider("KAKAO")
                .oauthId("kakao-test")
                .build();
    }

    private PointHistory history(String requestHash) {
        return PointHistory.builder()
                .memberId(MEMBER_ID)
                .type(PointHistoryType.REFUND_MARKET)
                .amount(AMOUNT)
                .balanceSnapshot(new BigDecimal("1300.00"))
                .reason("Market 무효 처리 환불")
                .referenceType(PointReferenceType.MARKET_PREDICTION)
                .referenceId(PREDICTION_ID)
                .idempotencyKey(KEY)
                .requestHash(requestHash)
                .status(PointTransactionStatus.SUCCEEDED)
                .build();
    }
}
