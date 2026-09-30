package com.todongsan.marketservice.market.service;

import com.todongsan.marketservice.global.config.PointTransportProperties;
import com.todongsan.marketservice.global.exception.CustomException;
import com.todongsan.marketservice.market.client.MemberPointClient;
import com.todongsan.marketservice.market.client.MemberPointRefundBatchRequest;
import com.todongsan.marketservice.market.client.MemberPointRefundBatchResponse;
import com.todongsan.marketservice.market.client.MemberPointRefundItem;
import com.todongsan.marketservice.market.client.exception.MemberPointExternalException;
import com.todongsan.marketservice.market.client.exception.MemberPointTimeoutException;
import com.todongsan.marketservice.market.client.exception.MemberPointUnavailableException;
import com.todongsan.marketservice.market.dto.response.RefundMarketResponse;
import com.todongsan.marketservice.market.dto.response.RetryRefundBatchResponse;
import com.todongsan.marketservice.market.entity.MarketRefundDetail;
import com.todongsan.marketservice.market.type.RefundStatus;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketRefundService {

    private static final String REFERENCE_TYPE = "MARKET_PREDICTION";
    static final String REASON = "Market 무효 처리 환불";
    private static final String RETRY_REASON = "Market 무효 처리 환불 재시도";

    private final MarketRefundTransactionService transactionService;
    private final MemberPointClient memberPointClient;
    private final PointTransportProperties pointTransportProperties;

    public RefundMarketResponse refundMarket(long marketId) {
        boolean async = pointTransportProperties.isAsync();
        MarketRefundPreparation preparation = transactionService.prepareRefund(marketId, async);
        if (preparation.completed()) {
            return preparation.toResponse(0, 0, 0, RefundStatus.COMPLETED);
        }
        if (async) {
            // 요청은 outbox에 기록됨. 결과는 Member-Point 결과 이벤트로 반영된다.
            return preparation.toResponse(0, 0, 0, RefundStatus.IN_PROGRESS);
        }

        String batchIdempotencyKey = batchIdempotencyKey(preparation);
        MemberPointRefundBatchRequest request = new MemberPointRefundBatchRequest(
                preparation.marketId(),
                batchIdempotencyKey,
                toItems(preparation.refundDetails(), REASON)
        );
        try {
            MemberPointRefundBatchResponse response = memberPointClient.refundMarketPredictions(
                    batchIdempotencyKey,
                    request
            );
            if (response == null || response.results() == null) {
                return transactionService.applyRefundUnknown(preparation, "MEMBER_POINT_RESULT_UNKNOWN");
            }
            return transactionService.applyRefundResult(preparation, response);
        } catch (MemberPointTimeoutException | MemberPointUnavailableException | MemberPointExternalException e) {
            return transactionService.applyRefundUnknown(preparation, failureReason(e, "MEMBER_POINT_RESULT_UNKNOWN"));
        }
    }

    public RefundMarketResponse retryRefundMarket(long marketId) {
        MarketRefundRetryPreparation preparation = transactionService.prepareRefundRetry(marketId);
        if (preparation.completed()) {
            return preparation.toResponse(0, 0, 0, RefundStatus.COMPLETED);
        }

        String batchIdempotencyKey = retryBatchIdempotencyKey(preparation);
        MemberPointRefundBatchRequest request = new MemberPointRefundBatchRequest(
                preparation.marketId(),
                batchIdempotencyKey,
                toItems(preparation.retryDetails(), RETRY_REASON)
        );
        try {
            MemberPointRefundBatchResponse response = memberPointClient.refundMarketPredictions(
                    batchIdempotencyKey,
                    request
            );
            if (response == null || response.results() == null) {
                return transactionService.applyRefundRetryUnknown(preparation, "MEMBER_POINT_RESULT_UNKNOWN");
            }
            return transactionService.applyRefundRetryResult(preparation, response);
        } catch (MemberPointTimeoutException | MemberPointUnavailableException | MemberPointExternalException e) {
            return transactionService.applyRefundRetryUnknown(preparation, failureReason(e, "MEMBER_POINT_RESULT_UNKNOWN"));
        }
    }

    public RetryRefundBatchResponse retryFailedRefunds(int limit) {
        List<Long> marketIds = transactionService.selectMarketIdsForRefundRetry(limit);
        RetryRefundBatchCounts counts = new RetryRefundBatchCounts(limit, marketIds.size());
        for (Long marketId : marketIds) {
            counts.retriedMarketCount++;
            try {
                RefundMarketResponse response = retryRefundMarket(marketId);
                if (response.refundStatus() == RefundStatus.COMPLETED) {
                    counts.completedMarketCount++;
                } else if (response.refundStatus() == RefundStatus.IN_PROGRESS) {
                    counts.stillInProgressCount++;
                } else {
                    counts.skippedCount++;
                }
            } catch (CustomException e) {
                counts.skippedCount++;
                log.info("Refund retry skipped. marketId={}, errorCode={}", marketId, e.getErrorCode().getCode());
            } catch (RuntimeException e) {
                counts.failedCount++;
                log.error("Refund retry failed. marketId={}", marketId, e);
            }
        }
        return counts.toResponse();
    }

    private List<MemberPointRefundItem> toItems(List<MarketRefundDetail> details, String reason) {
        return details.stream()
                .map(detail -> new MemberPointRefundItem(
                        detail.getPredictionId(),
                        detail.getMemberId(),
                        detail.getRefundAmount(),
                        REFERENCE_TYPE,
                        detail.getPredictionId(),
                        reason,
                        detail.getIdempotencyKey()
                ))
                .toList();
    }

    private String batchIdempotencyKey(MarketRefundPreparation preparation) {
        return "MARKET_REFUND_BATCH:market:%d:void:%d:attempt:1"
                .formatted(preparation.marketId(), preparation.voidId());
    }

    private String retryBatchIdempotencyKey(MarketRefundRetryPreparation preparation) {
        return "MARKET_REFUND_BATCH:market:%d:void:%d:retry:%s"
                .formatted(preparation.marketId(), preparation.voidId(), UUID.randomUUID());
    }

    private String failureReason(RuntimeException exception, String fallback) {
        String message = exception.getMessage();
        String reason = message == null || message.isBlank() ? fallback : message;
        return reason.length() <= 255 ? reason : reason.substring(0, 255);
    }

    private static class RetryRefundBatchCounts {
        private final int requestedLimit;
        private final int scannedMarketCount;
        private int retriedMarketCount;
        private int completedMarketCount;
        private int stillInProgressCount;
        private int skippedCount;
        private int failedCount;

        private RetryRefundBatchCounts(int requestedLimit, int scannedMarketCount) {
            this.requestedLimit = requestedLimit;
            this.scannedMarketCount = scannedMarketCount;
        }

        private RetryRefundBatchResponse toResponse() {
            return new RetryRefundBatchResponse(
                    requestedLimit,
                    scannedMarketCount,
                    retriedMarketCount,
                    completedMarketCount,
                    stillInProgressCount,
                    skippedCount,
                    failedCount
            );
        }
    }
}
