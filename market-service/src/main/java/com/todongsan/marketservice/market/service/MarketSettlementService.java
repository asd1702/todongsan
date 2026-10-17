package com.todongsan.marketservice.market.service;

import com.todongsan.marketservice.global.config.PointTransportProperties;
import com.todongsan.marketservice.global.exception.CustomException;
import com.todongsan.marketservice.market.client.MemberPointClient;
import com.todongsan.marketservice.market.client.MemberPointSettlementBatchRequest;
import com.todongsan.marketservice.market.client.MemberPointSettlementBatchResponse;
import com.todongsan.marketservice.market.client.MemberPointSettlementItem;
import com.todongsan.marketservice.market.client.exception.MemberPointExternalException;
import com.todongsan.marketservice.market.client.exception.MemberPointTimeoutException;
import com.todongsan.marketservice.market.client.exception.MemberPointUnavailableException;
import com.todongsan.marketservice.market.dto.response.RetrySettlementBatchResponse;
import com.todongsan.marketservice.market.dto.response.SettleMarketResponse;
import com.todongsan.marketservice.market.entity.MarketSettlementDetail;
import com.todongsan.marketservice.market.type.MarketStatus;
import com.todongsan.marketservice.market.type.SettlementStatus;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketSettlementService {

    private static final String REFERENCE_TYPE = "MARKET_PREDICTION";
    static final String REASON = "Market 정산 보상";
    static final String RETRY_REASON = "Market 정산 보상 재시도";

    private final MarketSettlementTransactionService transactionService;
    private final MemberPointClient memberPointClient;
    private final PointTransportProperties pointTransportProperties;

    public SettleMarketResponse settleMarket(long marketId) {
        boolean async = pointTransportProperties.isAsync();
        SettlementPreparation preparation = transactionService.prepareSettlement(marketId, async);
        if (preparation.completed()) {
            return preparation.toResponse(
                    MarketStatus.SETTLED,
                    SettlementStatus.COMPLETED,
                    0,
                    0
            );
        }
        if (async) {
            // 지급 요청은 outbox에 기록됨. 결과는 Member-Point 결과 이벤트로 반영된다.
            return preparation.toResponse(
                    MarketStatus.SETTLEMENT_IN_PROGRESS,
                    SettlementStatus.IN_PROGRESS,
                    0,
                    0
            );
        }

        String batchIdempotencyKey = batchIdempotencyKey(preparation);
        MemberPointSettlementBatchRequest request = new MemberPointSettlementBatchRequest(
                preparation.marketId(),
                batchIdempotencyKey,
                toItems(preparation.winnerDetails(), REASON)
        );
        try {
            MemberPointSettlementBatchResponse response = memberPointClient.settleMarketRewards(
                    batchIdempotencyKey,
                    request
            );
            if (response == null || response.results() == null) {
                return transactionService.applySettlementUnknown(preparation, "MEMBER_POINT_RESULT_UNKNOWN");
            }
            return transactionService.applySettlementResult(preparation, response);
        } catch (MemberPointTimeoutException | MemberPointUnavailableException | MemberPointExternalException e) {
            return transactionService.applySettlementUnknown(preparation, failureReason(e, "MEMBER_POINT_RESULT_UNKNOWN"));
        }
    }

    public SettleMarketResponse retryMarketSettlement(long marketId) {
        boolean async = pointTransportProperties.isAsync();
        SettlementRetryPreparation preparation = transactionService.prepareSettlementRetry(marketId, async);
        if (preparation.completed()) {
            return preparation.toResponse(
                    MarketStatus.SETTLED,
                    SettlementStatus.COMPLETED,
                    0,
                    0
            );
        }
        if (async) {
            // 재시도 요청은 outbox에 기록됨 (R5). 결과는 Member-Point 결과 이벤트로 반영된다.
            return preparation.toResponse(
                    MarketStatus.SETTLEMENT_IN_PROGRESS,
                    SettlementStatus.IN_PROGRESS,
                    0,
                    0
            );
        }

        String batchIdempotencyKey = retryBatchIdempotencyKey(preparation);
        MemberPointSettlementBatchRequest request = new MemberPointSettlementBatchRequest(
                preparation.marketId(),
                batchIdempotencyKey,
                toItems(preparation.retryDetails(), RETRY_REASON)
        );
        try {
            MemberPointSettlementBatchResponse response = memberPointClient.settleMarketRewards(
                    batchIdempotencyKey,
                    request
            );
            if (response == null || response.results() == null) {
                return transactionService.applySettlementRetryUnknown(preparation, "MEMBER_POINT_RESULT_UNKNOWN");
            }
            return transactionService.applySettlementRetryResult(preparation, response);
        } catch (MemberPointTimeoutException | MemberPointUnavailableException | MemberPointExternalException e) {
            return transactionService.applySettlementRetryUnknown(preparation, failureReason(e, "MEMBER_POINT_RESULT_UNKNOWN"));
        }
    }

    public RetrySettlementBatchResponse retryFailedSettlements(int limit) {
        List<Long> marketIds = transactionService.selectMarketIdsForSettlementRetry(limit);
        RetrySettlementBatchCounts counts = new RetrySettlementBatchCounts(limit, marketIds.size());
        for (Long marketId : marketIds) {
            counts.retriedMarketCount++;
            try {
                SettleMarketResponse response = retryMarketSettlement(marketId);
                if (response.marketStatus() == MarketStatus.SETTLED) {
                    counts.settledMarketCount++;
                } else if (response.marketStatus() == MarketStatus.SETTLEMENT_IN_PROGRESS) {
                    counts.stillInProgressCount++;
                } else {
                    counts.skippedCount++;
                }
            } catch (CustomException e) {
                counts.skippedCount++;
                log.info("Settlement retry skipped. marketId={}, errorCode={}", marketId, e.getErrorCode().getCode());
            } catch (RuntimeException e) {
                counts.failedCount++;
                log.error("Settlement retry failed. marketId={}", marketId, e);
            }
        }
        return counts.toResponse();
    }

    private List<MemberPointSettlementItem> toItems(List<MarketSettlementDetail> details, String reason) {
        return details.stream()
                .map(detail -> new MemberPointSettlementItem(
                        detail.getPredictionId(),
                        detail.getMemberId(),
                        detail.getSettledAmount(),
                        REFERENCE_TYPE,
                        detail.getPredictionId(),
                        reason,
                        detail.getIdempotencyKey()
                ))
                .toList();
    }

    private String batchIdempotencyKey(SettlementPreparation preparation) {
        return "MARKET_SETTLEMENT_BATCH:market:%d:settlement:%d:attempt:1"
                .formatted(preparation.marketId(), preparation.settlementId());
    }

    private String retryBatchIdempotencyKey(SettlementRetryPreparation preparation) {
        return "MARKET_SETTLEMENT_BATCH:market:%d:settlement:%d:retry:%s"
                .formatted(preparation.marketId(), preparation.settlementId(), UUID.randomUUID());
    }

    private String failureReason(RuntimeException exception, String fallback) {
        String message = exception.getMessage();
        String reason = message == null || message.isBlank() ? fallback : message;
        return reason.length() <= 255 ? reason : reason.substring(0, 255);
    }

    private static class RetrySettlementBatchCounts {
        private final int requestedLimit;
        private final int scannedMarketCount;
        private int retriedMarketCount;
        private int settledMarketCount;
        private int stillInProgressCount;
        private int skippedCount;
        private int failedCount;

        private RetrySettlementBatchCounts(int requestedLimit, int scannedMarketCount) {
            this.requestedLimit = requestedLimit;
            this.scannedMarketCount = scannedMarketCount;
        }

        private RetrySettlementBatchResponse toResponse() {
            return new RetrySettlementBatchResponse(
                    requestedLimit,
                    scannedMarketCount,
                    retriedMarketCount,
                    settledMarketCount,
                    stillInProgressCount,
                    skippedCount,
                    failedCount
            );
        }
    }
}
