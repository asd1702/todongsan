package com.todongsan.memberpointservice.point.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.todongsan.memberpointservice.member.entity.Member;
import com.todongsan.memberpointservice.member.repository.MemberRepository;
import com.todongsan.memberpointservice.point.dto.request.SpendRequest;
import com.todongsan.memberpointservice.point.dto.response.TransactionResponse;
import com.todongsan.memberpointservice.point.entity.PointHistory;
import com.todongsan.memberpointservice.point.entity.PointHistoryType;
import com.todongsan.memberpointservice.point.entity.PointReferenceType;
import com.todongsan.memberpointservice.point.entity.PointTransactionStatus;
import com.todongsan.memberpointservice.point.repository.PointHistoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 펜싱이 기록이 없음을 확인한 직후 spend가 먼저 커밋하면, 펜싱 INSERT가 idempotency_key UNIQUE에 걸린다.
 * 이때 펜싱은 실패하지 않고 먼저 커밋된 spend 기록(PROCESSED)을 반환해야 한다 (ASYNC_POINT_FLOW.md §12.6 동시 실행).
 */
@ExtendWith(MockitoExtension.class)
class PointSpendFenceRaceTest {

    private static final String KEY = "MARKET_PREDICTION_SPEND:market:7:member:1:attempt:1";

    @Mock MemberRepository memberRepository;
    @Mock PointHistoryRepository pointHistoryRepository;
    @Mock TransactionTemplate transactionTemplate;

    @Test
    void 펜싱_INSERT가_UNIQUE에_걸리면_먼저_커밋된_spend_결과를_반환한다() {
        PointInternalServiceImpl service = new PointInternalServiceImpl(memberRepository, pointHistoryRepository, transactionTemplate);
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                invocation.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
        when(pointHistoryRepository.findByIdempotencyKey(KEY))
                .thenReturn(Optional.empty())                 // 펜싱 트랜잭션 안: 아직 없음
                .thenReturn(Optional.of(committedSpend()));   // 충돌 후 재조회: spend가 먼저 커밋됨
        when(memberRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(member()));
        when(pointHistoryRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq_point_history_idempotency_key"));

        TransactionResponse response = service.fenceSpend(KEY, spendRequest());

        assertThat(response.getStatus()).isEqualTo("PROCESSED");
        assertThat(response.getFailReason()).isNull();
    }

    private static PointHistory committedSpend() {
        return PointHistory.builder()
                .memberId(1L)
                .type(PointHistoryType.SPEND_MARKET)
                .amount(new BigDecimal("300.00"))
                .balanceSnapshot(new BigDecimal("700.00"))
                .referenceType(PointReferenceType.MARKET_PREDICTION)
                .referenceId(1001L)
                .idempotencyKey(KEY)
                .status(PointTransactionStatus.SUCCEEDED)
                .build();
    }

    private static Member member() {
        return Member.builder().nickname("m").email("m@kakao.com").oauthProvider("KAKAO").oauthId("m").build();
    }

    private static SpendRequest spendRequest() {
        return new ObjectMapper().convertValue(Map.of(
                "memberId", 1L,
                "type", "SPEND_MARKET",
                "amount", new BigDecimal("300"),
                "referenceType", "MARKET_PREDICTION",
                "referenceId", 1001L,
                "reason", "Market 예측 참여"
        ), SpendRequest.class);
    }
}
