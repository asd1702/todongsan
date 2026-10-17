package com.todongsan.marketservice.market.service;

import com.todongsan.marketservice.global.exception.CustomException;
import com.todongsan.marketservice.global.exception.errorcode.MarketErrorCode;
import com.todongsan.marketservice.market.client.MemberPointRefundBatchResponse;
import com.todongsan.marketservice.market.client.MemberPointRefundItemResult;
import com.todongsan.marketservice.market.client.MemberPointRefundItemStatus;
import com.todongsan.marketservice.market.dto.response.RefundMarketResponse;
import com.todongsan.marketservice.market.entity.Market;
import com.todongsan.marketservice.market.entity.MarketPrediction;
import com.todongsan.marketservice.market.entity.MarketRefundDetail;
import com.todongsan.marketservice.market.entity.MarketVoid;
import com.todongsan.marketservice.market.outbox.PointRequestOutboxWriter;
import com.todongsan.marketservice.market.repository.MarketMapper;
import com.todongsan.marketservice.market.type.MarketStatus;
import com.todongsan.marketservice.market.type.RefundStatus;
import com.todongsan.marketservice.market.type.TransactionItemStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketRefundTransactionService {

    private static final int RETRY_PENDING_THRESHOLD_MINUTES = 3;

    private final MarketMapper marketMapper;
    private final PointRequestOutboxWriter pointRequestOutboxWriter;

    @Transactional
    public MarketRefundPreparation prepareRefund(long marketId, boolean publishPointRequests) {
        LocalDateTime now = LocalDateTime.now();
        Market market = marketMapper.lockMarketById(marketId);
        if (market == null) {
            throw new CustomException(MarketErrorCode.MARKET_NOT_FOUND);
        }
        if (market.getStatus() != MarketStatus.VOIDED) {
            throw new CustomException(MarketErrorCode.MARKET_INVALID_STATUS);
        }

        MarketVoid marketVoid = marketMapper.selectMarketVoidByMarketId(marketId);
        if (marketVoid == null) {
            throw new CustomException(MarketErrorCode.MARKET_INVALID_SETTLEMENT_DATA);
        }
        if (marketMapper.countRefundDetailsByVoidId(marketVoid.getId()) > 0) {
            throw new CustomException(MarketErrorCode.MARKET_ALREADY_REFUNDED);
        }

        List<MarketPrediction> targets = marketMapper.selectConfirmedPredictionsForRefund(marketId);
        if (targets.isEmpty()) {
            marketMapper.updateMarketVoidRefundStatus(marketVoid.getId(), RefundStatus.COMPLETED, now);
            return new MarketRefundPreparation(marketId, marketVoid.getId(), List.of(), true);
        }

        List<MarketRefundDetail> details = targets.stream()
                .map(prediction -> toPendingDetail(marketId, marketVoid.getId(), prediction, now))
                .toList();
        for (MarketRefundDetail detail : details) {
            if (marketMapper.updatePredictionToRefundPending(detail.getPredictionId(), now) != 1) {
                throw new CustomException(MarketErrorCode.MARKET_INVALID_SETTLEMENT_DATA);
            }
        }
        marketMapper.insertMarketRefundDetails(details);
        marketMapper.updateMarketVoidRefundStatus(marketVoid.getId(), RefundStatus.IN_PROGRESS, now);

        List<MarketRefundDetail> savedDetails = marketMapper.selectPendingRefundDetailsByVoidId(marketVoid.getId());
        if (publishPointRequests) {
            pointRequestOutboxWriter.appendRefundRequests(marketId, savedDetails, MarketRefundService.REASON, now);
        }
        return new MarketRefundPreparation(marketId, marketVoid.getId(), savedDetails, false);
    }

    @Transactional
    public RefundMarketResponse applyRefundResult(
            MarketRefundPreparation preparation,
            MemberPointRefundBatchResponse response
    ) {
        LocalDateTime now = LocalDateTime.now();
        Map<Long, MemberPointRefundItemResult> resultsByPredictionId = response.results().stream()
                .collect(Collectors.toMap(
                        MemberPointRefundItemResult::predictionId,
                        Function.identity(),
                        (first, ignored) -> first
                ));

        int successCount = 0;
        int failedCount = 0;
        int unknownCount = 0;
        for (MarketRefundDetail detail : preparation.refundDetails()) {
            MemberPointRefundItemResult result = resultsByPredictionId.get(detail.getPredictionId());
            if (result == null) {
                markRefundUnknown(detail, "MEMBER_POINT_RESULT_MISSING", now);
                unknownCount++;
                continue;
            }
            if (isSuccess(result.status())) {
                markRefundSuccess(detail, now);
                successCount++;
                continue;
            }
            if (isFailed(result.status())) {
                markRefundFailed(detail, failureReason(result.errorCode(), "MEMBER_POINT_FAILED"), now);
                failedCount++;
                continue;
            }
            markRefundUnknown(detail, failureReason(result.errorCode(), "MEMBER_POINT_STATUS_UNKNOWN"), now);
            unknownCount++;
        }

        RefundStatus refundStatus = failedCount == 0 && unknownCount == 0
                ? RefundStatus.COMPLETED
                : RefundStatus.IN_PROGRESS;
        marketMapper.updateMarketVoidRefundStatus(preparation.voidId(), refundStatus, now);
        return preparation.toResponse(successCount, failedCount, unknownCount, refundStatus);
    }

    @Transactional
    public RefundMarketResponse applyRefundUnknown(MarketRefundPreparation preparation, String failReason) {
        LocalDateTime now = LocalDateTime.now();
        for (MarketRefundDetail detail : preparation.refundDetails()) {
            markRefundUnknown(detail, failReason, now);
        }
        marketMapper.updateMarketVoidRefundStatus(preparation.voidId(), RefundStatus.IN_PROGRESS, now);
        return preparation.toResponse(0, 0, preparation.refundDetails().size(), RefundStatus.IN_PROGRESS);
    }

    @Transactional
    public MarketRefundRetryPreparation prepareRefundRetry(long marketId, boolean publishPointRequests) {
        LocalDateTime now = LocalDateTime.now();
        Market market = marketMapper.lockMarketById(marketId);
        if (market == null) {
            throw new CustomException(MarketErrorCode.MARKET_NOT_FOUND);
        }
        if (market.getStatus() != MarketStatus.VOIDED) {
            throw new CustomException(MarketErrorCode.MARKET_INVALID_STATUS);
        }

        MarketVoid marketVoid = marketMapper.selectMarketVoidByMarketId(marketId);
        if (marketVoid == null) {
            throw new CustomException(MarketErrorCode.MARKET_INVALID_SETTLEMENT_DATA);
        }
        long detailCount = marketMapper.countRefundDetailsByVoidId(marketVoid.getId());
        if (detailCount == 0) {
            throw new CustomException(MarketErrorCode.MARKET_REFUND_NOT_ALLOWED);
        }

        LocalDateTime pendingThreshold = now.minusMinutes(RETRY_PENDING_THRESHOLD_MINUTES);
        List<MarketRefundDetail> retryDetails = marketMapper.selectRetryableRefundDetails(
                marketVoid.getId(),
                pendingThreshold
        );
        if (retryDetails.isEmpty()) {
            if (marketMapper.countNonSuccessRefundDetails(marketVoid.getId()) == 0) {
                marketMapper.updateMarketVoidRefundStatus(marketVoid.getId(), RefundStatus.COMPLETED, now);
                return new MarketRefundRetryPreparation(marketId, marketVoid.getId(), List.of(), true);
            }
            throw new CustomException(MarketErrorCode.MARKET_REFUND_NOT_ALLOWED);
        }

        if (publishPointRequests) {
            // async 수동 재시도(R5): 같은 item 멱등성 키 + 새 eventId로 outbox에 다시 기록한다.
            for (MarketRefundDetail detail : retryDetails) {
                updateRetryDetail(detail, TransactionItemStatus.PENDING, null, now);
                marketMapper.markPredictionRefundPending(detail.getPredictionId(), null, now);
            }
            pointRequestOutboxWriter.appendRefundRequests(marketId, retryDetails, MarketRefundService.RETRY_REASON, now);
            marketMapper.updateMarketVoidRefundStatus(marketVoid.getId(), RefundStatus.IN_PROGRESS, now);
        }
        return new MarketRefundRetryPreparation(marketId, marketVoid.getId(), retryDetails, false);
    }

    /**
     * Member-Point 결과 이벤트(memberpoint.point-results)를 환불 detail에 반영한다.
     * SUCCESS는 종결 상태이므로 SQL 가드로 다시 바뀌지 않는다 (ASYNC_POINT_FLOW.md §13.3).
     */
    @Transactional
    public PointResultApplyOutcome applyAsyncRefundResult(
            long marketId,
            String idempotencyKey,
            boolean credited,
            String errorCode
    ) {
        // 락 순서: market → detail → market_void (prepareRefund / prepareRefundRetry와 동일)
        if (marketMapper.lockMarketById(marketId) == null) {
            return PointResultApplyOutcome.DETAIL_NOT_FOUND;
        }
        AppliedPointResult applied = applyRefundResultItem(idempotencyKey, credited, errorCode);
        if (applied.parentId() != null) {
            completeRefundIfDone(applied.parentId());
        }
        return applied.outcome();
    }

    /**
     * 호출자의 트랜잭션 안에서 환불 결과 1건을 detail에 반영한다.
     * market row 잠금과 완료 판정은 호출자가 한다 (배치 반영, ASYNC_POINT_FLOW.md §16.1).
     */
    public AppliedPointResult applyRefundResultItem(String idempotencyKey, boolean credited, String errorCode) {
        LocalDateTime now = LocalDateTime.now();
        MarketRefundDetail detail = marketMapper.selectRefundDetailByIdempotencyKeyForUpdate(idempotencyKey);
        if (detail == null) {
            return AppliedPointResult.detailNotFound();
        }
        boolean updated = credited
                ? markRetryRefundSuccess(detail, now)
                : markRetryRefundFailed(detail, failureReason(errorCode, "MEMBER_POINT_FAILED"), now);
        return new AppliedPointResult(
                updated ? PointResultApplyOutcome.APPLIED : PointResultApplyOutcome.SKIPPED,
                detail.getMarketVoidId()
        );
    }

    /** 호출자의 트랜잭션 안에서 무효 처리의 환불 완료 여부를 판정한다. */
    public void completeRefundIfDone(long voidId) {
        resolveRefundStatus(voidId, LocalDateTime.now());
    }

    @Transactional(readOnly = true)
    public List<Long> selectMarketIdsForRefundRetry(int limit) {
        LocalDateTime pendingThreshold = LocalDateTime.now().minusMinutes(RETRY_PENDING_THRESHOLD_MINUTES);
        return marketMapper.selectMarketIdsForRefundRetry(pendingThreshold, limit);
    }

    @Transactional
    public RefundMarketResponse applyRefundRetryResult(
            MarketRefundRetryPreparation preparation,
            MemberPointRefundBatchResponse response
    ) {
        LocalDateTime now = LocalDateTime.now();
        Map<Long, MemberPointRefundItemResult> resultsByPredictionId = response.results().stream()
                .collect(Collectors.toMap(
                        MemberPointRefundItemResult::predictionId,
                        Function.identity(),
                        (first, ignored) -> first
                ));

        int successCount = 0;
        int failedCount = 0;
        int unknownCount = 0;
        for (MarketRefundDetail detail : preparation.retryDetails()) {
            MemberPointRefundItemResult result = resultsByPredictionId.get(detail.getPredictionId());
            if (result == null) {
                if (markRetryRefundUnknown(detail, "MEMBER_POINT_RESULT_MISSING", now)) {
                    unknownCount++;
                }
                continue;
            }
            if (result.status() == null) {
                if (markRetryRefundUnknown(detail, "MEMBER_POINT_RESULT_STATUS_MISSING", now)) {
                    unknownCount++;
                }
                continue;
            }
            if (isSuccess(result.status())) {
                if (markRetryRefundSuccess(detail, now)) {
                    successCount++;
                }
                continue;
            }
            if (isFailed(result.status())) {
                if (markRetryRefundFailed(detail, failureReason(result.errorCode(), "MEMBER_POINT_FAILED"), now)) {
                    failedCount++;
                }
                continue;
            }
            if (markRetryRefundUnknown(detail, failureReason(result.errorCode(), "MEMBER_POINT_STATUS_UNKNOWN"), now)) {
                unknownCount++;
            }
        }

        RefundStatus refundStatus = resolveRefundStatus(preparation.voidId(), now);
        return preparation.toResponse(successCount, failedCount, unknownCount, refundStatus);
    }

    @Transactional
    public RefundMarketResponse applyRefundRetryUnknown(
            MarketRefundRetryPreparation preparation,
            String failReason
    ) {
        LocalDateTime now = LocalDateTime.now();
        int unknownCount = 0;
        for (MarketRefundDetail detail : preparation.retryDetails()) {
            if (markRetryRefundUnknown(detail, failReason, now)) {
                unknownCount++;
            }
        }
        marketMapper.updateMarketVoidRefundStatus(preparation.voidId(), RefundStatus.IN_PROGRESS, now);
        return preparation.toResponse(0, 0, unknownCount, RefundStatus.IN_PROGRESS);
    }

    private MarketRefundDetail toPendingDetail(
            long marketId,
            Long voidId,
            MarketPrediction prediction,
            LocalDateTime now
    ) {
        MarketRefundDetail detail = new MarketRefundDetail();
        detail.setMarketVoidId(voidId);
        detail.setPredictionId(prediction.getId());
        detail.setMemberId(prediction.getMemberId());
        detail.setRefundAmount(prediction.getPointAmount());
        detail.setStatus(TransactionItemStatus.PENDING);
        detail.setIdempotencyKey(refundIdempotencyKey(marketId, prediction));
        detail.setCreatedAt(now);
        detail.setUpdatedAt(now);
        return detail;
    }

    private void markRefundSuccess(MarketRefundDetail detail, LocalDateTime now) {
        marketMapper.updateRefundDetailStatus(detail.getId(), TransactionItemStatus.SUCCESS, null, now);
        marketMapper.markPredictionRefunded(detail.getPredictionId(), detail.getRefundAmount(), now);
    }

    private void markRefundFailed(MarketRefundDetail detail, String failReason, LocalDateTime now) {
        marketMapper.updateRefundDetailStatus(detail.getId(), TransactionItemStatus.FAILED, failReason, now);
    }

    private void markRefundUnknown(MarketRefundDetail detail, String failReason, LocalDateTime now) {
        marketMapper.updateRefundDetailStatus(detail.getId(), TransactionItemStatus.UNKNOWN, failReason, now);
        marketMapper.markPredictionRefundUnknown(detail.getPredictionId(), failReason, now);
    }

    private boolean markRetryRefundSuccess(MarketRefundDetail detail, LocalDateTime now) {
        if (!updateRetryDetail(detail, TransactionItemStatus.SUCCESS, null, now)) {
            return false;
        }
        int updatedRows = marketMapper.markPredictionRefunded(detail.getPredictionId(), detail.getRefundAmount(), now);
        logSkippedPredictionUpdateIfNeeded(detail, "REFUNDED", updatedRows);
        return true;
    }

    private boolean markRetryRefundFailed(MarketRefundDetail detail, String failReason, LocalDateTime now) {
        if (!updateRetryDetail(detail, TransactionItemStatus.FAILED, failReason, now)) {
            return false;
        }
        int updatedRows = marketMapper.markPredictionRefundPending(detail.getPredictionId(), failReason, now);
        logSkippedPredictionUpdateIfNeeded(detail, "REFUND_PENDING", updatedRows);
        return true;
    }

    private boolean markRetryRefundUnknown(MarketRefundDetail detail, String failReason, LocalDateTime now) {
        if (!updateRetryDetail(detail, TransactionItemStatus.UNKNOWN, failReason, now)) {
            return false;
        }
        int updatedRows = marketMapper.markPredictionRefundUnknown(detail.getPredictionId(), failReason, now);
        logSkippedPredictionUpdateIfNeeded(detail, "REFUND_UNKNOWN", updatedRows);
        return true;
    }

    private String refundIdempotencyKey(long marketId, MarketPrediction prediction) {
        return "MARKET_REFUND:market:%d:prediction:%d:member:%d"
                .formatted(marketId, prediction.getId(), prediction.getMemberId());
    }

    private boolean isSuccess(MemberPointRefundItemStatus status) {
        return status == MemberPointRefundItemStatus.PROCESSED
                || status == MemberPointRefundItemStatus.ALREADY_PROCESSED;
    }

    private boolean isFailed(MemberPointRefundItemStatus status) {
        return status == MemberPointRefundItemStatus.FAILED;
    }

    private RefundStatus resolveRefundStatus(Long voidId, LocalDateTime now) {
        RefundStatus refundStatus = marketMapper.countNonSuccessRefundDetails(voidId) == 0
                ? RefundStatus.COMPLETED
                : RefundStatus.IN_PROGRESS;
        marketMapper.updateMarketVoidRefundStatus(voidId, refundStatus, now);
        return refundStatus;
    }

    private boolean updateRetryDetail(
            MarketRefundDetail detail,
            TransactionItemStatus status,
            String failReason,
            LocalDateTime now
    ) {
        int updatedRows = marketMapper.updateRetryRefundDetailStatus(detail.getId(), status, failReason, now);
        if (updatedRows == 0) {
            log.warn(
                    "Refund retry detail update skipped. detailId={}, predictionId={}, targetStatus={}",
                    detail.getId(),
                    detail.getPredictionId(),
                    status
            );
            return false;
        }
        return true;
    }

    private void logSkippedPredictionUpdateIfNeeded(
            MarketRefundDetail detail,
            String targetStatus,
            int updatedRows
    ) {
        if (updatedRows == 0) {
            log.warn(
                    "Refund retry prediction update skipped. detailId={}, predictionId={}, targetStatus={}",
                    detail.getId(),
                    detail.getPredictionId(),
                    targetStatus
            );
        }
    }

    private String failureReason(String reason, String fallback) {
        String value = reason == null || reason.isBlank() ? fallback : reason;
        return value.length() <= 255 ? value : value.substring(0, 255);
    }
}
