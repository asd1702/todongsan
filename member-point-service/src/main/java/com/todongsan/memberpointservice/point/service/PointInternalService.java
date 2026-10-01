package com.todongsan.memberpointservice.point.service;

import com.todongsan.memberpointservice.point.dto.request.EarnRequest;
import com.todongsan.memberpointservice.point.dto.request.RefundRequest;
import com.todongsan.memberpointservice.point.dto.request.SettlementRequest;
import com.todongsan.memberpointservice.point.dto.request.SpendRequest;
import com.todongsan.memberpointservice.point.dto.response.*;
import com.todongsan.memberpointservice.point.entity.PointHistoryType;

import java.math.BigDecimal;

public interface PointInternalService {

    PointResult<EarnResponse> earn(String idempotencyKey, EarnRequest request);

    PointResult<SpendResponse> spend(String idempotencyKey, SpendRequest request);

    TransactionResponse getTransaction(String idempotencyKey);

    /** 대사가 NOT_FOUND를 확인한 차감 요청을 실패로 확정한다. 이후 같은 키의 spend는 차감하지 못한다. */
    TransactionResponse fenceSpend(String idempotencyKey, SpendRequest request);

    SettlementResponse settle(String idempotencyKey, SettlementRequest request);

    RefundResponse refund(String idempotencyKey, RefundRequest request);

    /** 포인트 적립 요청 이벤트(market.refund-requests, market.settlement-payout-requests) 1건을 적립 처리한다. item 멱등성 키로 중복 처리를 막는다. */
    BatchItemResult creditMarketPoint(PointHistoryType type, Long predictionId, Long memberId, BigDecimal amount,
                                      String referenceType, Long referenceId, String reason, String idempotencyKey);

}
