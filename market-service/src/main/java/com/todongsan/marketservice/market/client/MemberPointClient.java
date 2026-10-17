package com.todongsan.marketservice.market.client;

public interface MemberPointClient {
    void spend(PointSpendCommand command);

    MemberPointTransactionStatusResponse getTransactionStatus(String idempotencyKey);

    /** 차감 기록이 없으면 실패로 확정해 늦게 도착한 spend를 막는다. 원래 spend와 같은 요청 내용을 보낸다 (ASYNC_POINT_FLOW.md §12.6). */
    MemberPointTransactionStatusResponse fenceSpend(PointSpendCommand command);

    MemberPointSettlementBatchResponse settleMarketRewards(
            String batchIdempotencyKey,
            MemberPointSettlementBatchRequest request
    );

    MemberPointRefundBatchResponse refundMarketPredictions(
            String batchIdempotencyKey,
            MemberPointRefundBatchRequest request
    );
}
