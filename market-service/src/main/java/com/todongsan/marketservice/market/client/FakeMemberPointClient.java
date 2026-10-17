package com.todongsan.marketservice.market.client;

import com.todongsan.marketservice.market.client.exception.MemberPointTimeoutException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "client.member-point.mode",
        havingValue = "fake",
        matchIfMissing = true
)
public class FakeMemberPointClient implements MemberPointClient {

    private final Map<String, MemberPointTransactionStatusResponse> transactionStatuses = new HashMap<>();
    private final Map<String, RuntimeException> transactionStatusExceptions = new HashMap<>();

    @Override
    public void spend(PointSpendCommand command) {
        // Phase 1 keeps the transaction boundary while Member-Point HTTP integration is deferred.
    }

    @Override
    public MemberPointTransactionStatusResponse getTransactionStatus(String idempotencyKey) {
        RuntimeException exception = transactionStatusExceptions.get(idempotencyKey);
        if (exception != null) {
            throw exception;
        }
        return transactionStatuses.getOrDefault(
                idempotencyKey,
                new MemberPointTransactionStatusResponse(
                        idempotencyKey,
                        MemberPointTransactionStatus.NOT_FOUND,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                )
        );
    }

    /** 기록이 없으면 FAILED(POINT_SPEND_FENCED)로 남기고, 있으면 그대로 돌려준다. */
    @Override
    public MemberPointTransactionStatusResponse fenceSpend(PointSpendCommand command) {
        MemberPointTransactionStatusResponse current = getTransactionStatus(command.idempotencyKey());
        if (current.status() != MemberPointTransactionStatus.NOT_FOUND) {
            return current;
        }
        MemberPointTransactionStatusResponse fenced = new MemberPointTransactionStatusResponse(
                command.idempotencyKey(),
                MemberPointTransactionStatus.FAILED,
                command.memberId(),
                command.type(),
                command.amount(),
                command.referenceType(),
                command.referenceId(),
                null,
                null,
                "POINT_SPEND_FENCED"
        );
        transactionStatuses.put(command.idempotencyKey(), fenced);
        return fenced;
    }

    public void setTransactionStatus(String idempotencyKey, MemberPointTransactionStatusResponse response) {
        transactionStatuses.put(idempotencyKey, response);
        transactionStatusExceptions.remove(idempotencyKey);
    }

    public void throwTransactionStatusTimeout(String idempotencyKey) {
        transactionStatusExceptions.put(idempotencyKey, new MemberPointTimeoutException("MEMBER_POINT_TIMEOUT"));
    }

    public void clearTransactionStatuses() {
        transactionStatuses.clear();
        transactionStatusExceptions.clear();
    }

    @Override
    public MemberPointSettlementBatchResponse settleMarketRewards(
            String batchIdempotencyKey,
            MemberPointSettlementBatchRequest request
    ) {
        List<MemberPointSettlementItemResult> results = request.items().stream()
                .map(item -> new MemberPointSettlementItemResult(
                        item.predictionId(),
                        item.memberId(),
                        MemberPointSettlementItemStatus.PROCESSED,
                        null,
                        item.amount(),
                        null
                ))
                .toList();
        return new MemberPointSettlementBatchResponse(request.marketId(), results);
    }

    @Override
    public MemberPointRefundBatchResponse refundMarketPredictions(
            String batchIdempotencyKey,
            MemberPointRefundBatchRequest request
    ) {
        List<MemberPointRefundItemResult> results = request.items().stream()
                .map(item -> new MemberPointRefundItemResult(
                        item.predictionId(),
                        item.memberId(),
                        MemberPointRefundItemStatus.PROCESSED,
                        null,
                        item.amount(),
                        null
                ))
                .toList();
        return new MemberPointRefundBatchResponse(request.marketId(), results);
    }
}
