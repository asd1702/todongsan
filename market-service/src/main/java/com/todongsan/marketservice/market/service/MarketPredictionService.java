package com.todongsan.marketservice.market.service;

import com.todongsan.marketservice.global.exception.CustomException;
import com.todongsan.marketservice.global.exception.errorcode.CommonErrorCode;
import com.todongsan.marketservice.global.exception.errorcode.MarketErrorCode;
import com.todongsan.marketservice.global.exception.errorcode.PointErrorCode;
import com.todongsan.marketservice.market.client.MemberPointClient;
import com.todongsan.marketservice.market.client.MemberPointSpendCircuitBreaker;
import com.todongsan.marketservice.market.client.PointSpendCommand;
import com.todongsan.marketservice.market.client.exception.MemberPointExternalException;
import com.todongsan.marketservice.market.client.exception.MemberPointTimeoutException;
import com.todongsan.marketservice.market.client.exception.MemberPointUnavailableException;
import com.todongsan.marketservice.market.client.exception.PointInsufficientException;
import com.todongsan.marketservice.market.debug.MarketPredictionDeadlockDebug;
import com.todongsan.marketservice.market.dto.request.CreatePredictionRequest;
import com.todongsan.marketservice.market.dto.request.QuoteMarketPredictionRequest;
import com.todongsan.marketservice.market.dto.response.CreatePredictionResponse;
import com.todongsan.marketservice.market.dto.response.QuoteMarketPredictionResponse;
import com.todongsan.marketservice.market.entity.MarketPrediction;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MarketPredictionService {

    static final String SPEND_TYPE = "SPEND_MARKET";
    static final String REFERENCE_TYPE = "MARKET_PREDICTION";

    private final MarketPredictionTransactionService transactionService;
    private final MemberPointClient memberPointClient;
    private final MarketPredictionDeadlockDebug deadlockDebug;
    private final MemberPointSpendCircuitBreaker spendCircuitBreaker;

    public CreatePredictionResponse createPrediction(
            long marketId,
            Long memberId,
            String idempotencyKey,
            CreatePredictionRequest request
    ) {
        validateHeaders(marketId, memberId, idempotencyKey);

        // 예측 생성 전에 서킷 허가를 받는다. MP가 아프면 DB를 건드리지 않고 503 (SPEND_CIRCUIT_BREAKER.md B2·B3)
        try (MemberPointSpendCircuitBreaker.Permit permit = spendCircuitBreaker.acquire()) {
            MarketPrediction prediction;
            try {
                prediction = transactionService.createPendingPrediction(
                        marketId,
                        memberId,
                        idempotencyKey,
                        request
                );
            } catch (DuplicateKeyException e) {
                throw new CustomException(MarketErrorCode.MARKET_ALREADY_PREDICTED);
            }
            try {
                deadlockDebug.phase("POINT_SPEND_START", prediction.getId());
                permit.callStarted();
                memberPointClient.spend(new PointSpendCommand(
                        memberId,
                        SPEND_TYPE,
                        prediction.getPointAmount(),
                        REFERENCE_TYPE,
                        prediction.getId(),
                        prediction.getPointSpendIdempotencyKey()
                ));
                permit.responded();
                deadlockDebug.phase("POINT_SPEND_END", prediction.getId());
            } catch (PointInsufficientException e) {
                permit.responded();
                transactionService.markPredictionFailed(prediction.getId(), failureReason(e, "POINT_INSUFFICIENT"));
                throw new CustomException(PointErrorCode.POINT_INSUFFICIENT);
            } catch (MemberPointTimeoutException | MemberPointUnavailableException | MemberPointExternalException e) {
                permit.failed(e);
                return toResponse(transactionService.markPredictionUnknown(
                        prediction.getId(),
                        failureReason(e, "MEMBER_POINT_STATUS_UNKNOWN")
                ));
            }
            return transactionService.confirmPrediction(prediction.getId());
        }
    }

    public QuoteMarketPredictionResponse quotePrediction(
            long marketId,
            QuoteMarketPredictionRequest request
    ) {
        return transactionService.quotePrediction(marketId, request);
    }

    private CreatePredictionResponse toResponse(MarketPrediction prediction) {
        return new CreatePredictionResponse(
                prediction.getId(),
                prediction.getMarketId(),
                prediction.getOptionId(),
                prediction.getPointAmount(),
                prediction.getPriceSnapshot(),
                prediction.getContractQuantity(),
                prediction.getStatus()
        );
    }

    private String failureReason(RuntimeException exception, String fallback) {
        String message = exception.getMessage();
        String reason = message == null || message.isBlank() ? fallback : message;
        return reason.length() <= 255 ? reason : reason.substring(0, 255);
    }

    private void validateHeaders(long marketId, Long memberId, String idempotencyKey) {
        if (memberId == null || memberId <= 0) {
            throw new CustomException(CommonErrorCode.VALIDATION_FAILED);
        }
        String expectedIdempotencyKey = "MARKET_PREDICTION_SPEND:market:%d:member:%d"
                .formatted(marketId, memberId);
        if (!expectedIdempotencyKey.equals(idempotencyKey)) {
            throw new CustomException(CommonErrorCode.VALIDATION_FAILED);
        }
    }
}
