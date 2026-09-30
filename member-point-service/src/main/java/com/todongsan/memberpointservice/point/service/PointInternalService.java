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

    SettlementResponse settle(String idempotencyKey, SettlementRequest request);

    RefundResponse refund(String idempotencyKey, RefundRequest request);

    /** market.point-requests 이벤트 1건을 적립 처리한다. item 멱등성 키로 중복 처리를 막는다. */
    BatchItemResult creditMarketPoint(PointHistoryType type, Long predictionId, Long memberId, BigDecimal amount,
                                      String referenceType, Long referenceId, String reason, String idempotencyKey);

}
