package com.todongsan.marketservice.market.service;

import com.todongsan.marketservice.market.client.MemberPointClient;
import com.todongsan.marketservice.market.client.MemberPointTransactionStatus;
import com.todongsan.marketservice.market.client.MemberPointTransactionStatusResponse;
import com.todongsan.marketservice.market.client.PointSpendCommand;
import com.todongsan.marketservice.market.client.exception.MemberPointExternalException;
import com.todongsan.marketservice.market.client.exception.MemberPointTimeoutException;
import com.todongsan.marketservice.market.client.exception.MemberPointUnavailableException;
import com.todongsan.marketservice.market.dto.response.ReconcilePredictionSpendResponse;
import com.todongsan.marketservice.market.entity.MarketPrediction;
import com.todongsan.marketservice.market.service.MarketPredictionTransactionService.ReconciliationStatusUpdateResult;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PredictionSpendReconciliationService {

    private static final int PENDING_STALE_MINUTES = 3;
    private static final String TRANSACTION_FAILED = "TRANSACTION_FAILED";
    private static final String MEMBER_POINT_RESULT_UNKNOWN = "MEMBER_POINT_RESULT_UNKNOWN";

    private final MarketPredictionTransactionService transactionService;
    private final MemberPointClient memberPointClient;

    public ReconcilePredictionSpendResponse reconcile(int limit) {
        LocalDateTime pendingThreshold = LocalDateTime.now().minusMinutes(PENDING_STALE_MINUTES);
        List<MarketPrediction> targets = transactionService.selectPredictionsForSpendReconciliation(
                pendingThreshold,
                limit
        );
        ReconcileCounts counts = new ReconcileCounts(limit, targets.size());

        for (MarketPrediction prediction : targets) {
            if (hasMissingRequiredData(prediction)) {
                counts.skippedCount++;
                continue;
            }
            counts.processedCount++;
            reconcilePrediction(prediction, counts);
        }
        return counts.toResponse();
    }

    private void reconcilePrediction(MarketPrediction prediction, ReconcileCounts counts) {
        MemberPointTransactionStatusResponse response;
        try {
            response = memberPointClient.getTransactionStatus(prediction.getPointSpendIdempotencyKey());
            if (response != null && response.status() == MemberPointTransactionStatus.NOT_FOUND) {
                counts.notFoundCount++;
                // 바로 FAILED로 종결하면 늦게 도착한 spend가 차감만 남긴다. MP에 실패를 먼저 확정한다 (ASYNC_POINT_FLOW.md §12.6)
                response = memberPointClient.fenceSpend(spendCommand(prediction));
            }
        } catch (MemberPointTimeoutException | MemberPointUnavailableException | MemberPointExternalException e) {
            markUnknown(prediction.getId(), failureReason(e, MEMBER_POINT_RESULT_UNKNOWN), counts);
            return;
        }

        MemberPointTransactionStatus status = response == null ? null : response.status();
        if (status == MemberPointTransactionStatus.PROCESSED) {
            if (transactionService.confirmPredictionForReconciliation(prediction.getId())) {
                counts.confirmedCount++;
            } else {
                counts.skippedCount++;
            }
            return;
        }
        if (status == MemberPointTransactionStatus.FAILED) {
            if (transactionService.failPredictionForReconciliation(
                    prediction.getId(),
                    failureReason(response.errorCode(), TRANSACTION_FAILED)
            )) {
                counts.failedCount++;
            } else {
                counts.skippedCount++;
            }
            return;
        }
        markUnknown(prediction.getId(), MEMBER_POINT_RESULT_UNKNOWN, counts);
    }

    /** 원래 spend와 같은 요청 내용이어야 MP request_hash가 일치한다 (MarketPredictionService.createPrediction). */
    private static PointSpendCommand spendCommand(MarketPrediction prediction) {
        return new PointSpendCommand(
                prediction.getMemberId(),
                MarketPredictionService.SPEND_TYPE,
                prediction.getPointAmount(),
                MarketPredictionService.REFERENCE_TYPE,
                prediction.getId(),
                prediction.getPointSpendIdempotencyKey()
        );
    }

    private void markUnknown(long predictionId, String failReason, ReconcileCounts counts) {
        ReconciliationStatusUpdateResult result = transactionService.markPredictionUnknownForReconciliation(
                predictionId,
                failReason
        );
        if (result == ReconciliationStatusUpdateResult.SKIPPED) {
            counts.skippedCount++;
            return;
        }
        counts.unknownCount++;
    }

    private boolean hasMissingRequiredData(MarketPrediction prediction) {
        return prediction.getPointSpendIdempotencyKey() == null
                || prediction.getPointSpendIdempotencyKey().isBlank()
                || prediction.getMarketId() == null
                || prediction.getOptionId() == null
                || prediction.getMemberId() == null
                || prediction.getPointAmount() == null;
    }

    private String failureReason(RuntimeException exception, String fallback) {
        return failureReason(exception.getMessage(), fallback);
    }

    private String failureReason(String reason, String fallback) {
        String value = reason == null || reason.isBlank() ? fallback : reason;
        return value.length() <= 255 ? value : value.substring(0, 255);
    }

    private static class ReconcileCounts {
        private final int requestedLimit;
        private final int scannedCount;
        private int processedCount;
        private int confirmedCount;
        private int failedCount;
        private int notFoundCount;
        private int unknownCount;
        private int skippedCount;

        private ReconcileCounts(int requestedLimit, int scannedCount) {
            this.requestedLimit = requestedLimit;
            this.scannedCount = scannedCount;
        }

        private ReconcilePredictionSpendResponse toResponse() {
            return new ReconcilePredictionSpendResponse(
                    requestedLimit,
                    scannedCount,
                    processedCount,
                    confirmedCount,
                    failedCount,
                    notFoundCount,
                    unknownCount,
                    skippedCount
            );
        }
    }
}
