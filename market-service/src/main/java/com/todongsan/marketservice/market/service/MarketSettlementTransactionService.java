package com.todongsan.marketservice.market.service;

import com.todongsan.marketservice.global.exception.CustomException;
import com.todongsan.marketservice.global.exception.errorcode.MarketErrorCode;
import com.todongsan.marketservice.market.client.MemberPointSettlementBatchResponse;
import com.todongsan.marketservice.market.client.MemberPointSettlementItemResult;
import com.todongsan.marketservice.market.client.MemberPointSettlementItemStatus;
import com.todongsan.marketservice.market.dto.response.SettleMarketResponse;
import com.todongsan.marketservice.market.entity.Market;
import com.todongsan.marketservice.market.entity.MarketOption;
import com.todongsan.marketservice.market.entity.MarketPrediction;
import com.todongsan.marketservice.market.entity.MarketSettlement;
import com.todongsan.marketservice.market.entity.MarketSettlementDetail;
import com.todongsan.marketservice.market.outbox.PointRequestOutboxWriter;
import com.todongsan.marketservice.market.repository.MarketMapper;
import com.todongsan.marketservice.market.type.MarketStatus;
import com.todongsan.marketservice.market.type.SettlementStatus;
import com.todongsan.marketservice.market.type.TransactionItemStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class MarketSettlementTransactionService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal ZERO_AMOUNT = new BigDecimal("0.00");
    private static final BigDecimal ZERO_QUANTITY = new BigDecimal("0.00000000");
    private static final int AMOUNT_SCALE = 2;
    private static final int QUANTITY_SCALE = 8;
    private static final int RETRY_PENDING_THRESHOLD_MINUTES = 3;

    private final MarketMapper marketMapper;
    private final PointRequestOutboxWriter pointRequestOutboxWriter;

    @Transactional
    public SettlementPreparation prepareSettlement(long marketId, boolean publishPointRequests) {
        LocalDateTime now = LocalDateTime.now();
        if (marketMapper.startSettlement(marketId, now) != 1) {
            throwSettlementStartError(marketId);
        }

        Market market = marketMapper.lockMarketById(marketId);
        if (market == null) {
            throw new CustomException(MarketErrorCode.MARKET_NOT_FOUND);
        }
        validateSettlementData(market);

        List<MarketPrediction> predictions = marketMapper.selectConfirmedPredictionsForSettlement(marketId);
        if (predictions.isEmpty()) {
            throw new CustomException(MarketErrorCode.MARKET_NO_PREDICTIONS);
        }

        List<MarketPrediction> winners = predictions.stream()
                .filter(prediction -> market.getResultOptionId().equals(prediction.getOptionId()))
                .toList();
        int loserCount = predictions.size() - winners.size();

        BigDecimal totalPool = floorAmount(predictions.stream()
                .map(MarketPrediction::getPointAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal winningPrincipalPool = floorAmount(winners.stream()
                .map(MarketPrediction::getPointAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal losingPool = floorAmount(totalPool.subtract(winningPrincipalPool));
        BigDecimal feeAmount = floorAmount(
                losingPool.multiply(market.getFeeRate()).divide(HUNDRED, AMOUNT_SCALE, RoundingMode.DOWN)
        );
        BigDecimal rewardPool = floorAmount(losingPool.subtract(feeAmount));
        BigDecimal settlementPool = floorAmount(winningPrincipalPool.add(rewardPool));
        BigDecimal winningContractQuantity = winners.stream()
                .map(this::contractQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(QUANTITY_SCALE, RoundingMode.DOWN);
        BigDecimal rewardPerContract = winningContractQuantity.compareTo(BigDecimal.ZERO) > 0
                ? rewardPool.divide(winningContractQuantity, QUANTITY_SCALE, RoundingMode.DOWN)
                : ZERO_QUANTITY;

        MarketSettlement settlement = createSettlement(
                market,
                totalPool,
                feeAmount,
                settlementPool,
                winningContractQuantity,
                rewardPerContract,
                now
        );
        List<MarketSettlementDetail> details = winners.stream()
                .map(prediction -> toPendingDetail(marketId, settlement.getId(), prediction, rewardPerContract, now))
                .toList();

        BigDecimal burnedPointAmount = floorAmount(settlementPool.subtract(details.stream()
                .map(MarketSettlementDetail::getSettledAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)));
        settlement.setBurnedPointAmount(burnedPointAmount);
        marketMapper.insertMarketSettlement(settlement);

        List<MarketSettlementDetail> savedDetails = List.of();
        if (!details.isEmpty()) {
            details.forEach(detail -> detail.setSettlementId(settlement.getId()));
            marketMapper.insertMarketSettlementDetails(details);
            savedDetails = marketMapper.selectSettlementDetailsBySettlementId(settlement.getId());
        }
        marketMapper.updateMarketSettlementAmounts(marketId, feeAmount, settlementPool, now);

        boolean completed = savedDetails.isEmpty();
        if (completed) {
            marketMapper.settleAllConfirmedPredictionsZero(marketId, now);
            marketMapper.completeMarketSettlement(settlement.getId(), now);
            marketMapper.completeMarket(marketId, now);
            createReputationUpdateTasksForSettledMarket(marketId, now);
        } else if (publishPointRequests) {
            // async: 승자 지급 요청은 outbox로, 패자 처리는 MP와 무관하므로 준비 트랜잭션에서 끝낸다 (ASYNC_POINT_FLOW.md §14 S1)
            pointRequestOutboxWriter.appendSettlementPayoutRequests(marketId, savedDetails, MarketSettlementService.REASON, now);
            marketMapper.settleLoserPredictions(marketId, market.getResultOptionId(), now);
        }

        return new SettlementPreparation(
                marketId,
                settlement.getId(),
                market.getResultOptionId(),
                totalPool,
                feeAmount,
                settlementPool,
                winningContractQuantity,
                rewardPerContract,
                burnedPointAmount,
                winners.size(),
                loserCount,
                savedDetails,
                completed
        );
    }

    @Transactional
    public SettleMarketResponse applySettlementResult(
            SettlementPreparation preparation,
            MemberPointSettlementBatchResponse response
    ) {
        LocalDateTime now = LocalDateTime.now();
        Map<Long, MemberPointSettlementItemResult> resultsByPredictionId = response.results().stream()
                .collect(Collectors.toMap(
                        MemberPointSettlementItemResult::predictionId,
                        Function.identity(),
                        (first, ignored) -> first
                ));

        int successCount = 0;
        int failedCount = 0;
        for (MarketSettlementDetail detail : preparation.winnerDetails()) {
            MemberPointSettlementItemResult result = resultsByPredictionId.get(detail.getPredictionId());
            if (result == null) {
                markDetail(detail, TransactionItemStatus.UNKNOWN, "MEMBER_POINT_RESULT_MISSING", now);
                failedCount++;
                continue;
            }
            if (isSuccess(result.status())) {
                markDetail(detail, TransactionItemStatus.SUCCESS, null, now);
                marketMapper.settlePrediction(detail.getPredictionId(), detail.getSettledAmount(), now);
                successCount++;
                continue;
            }
            if (isFailed(result.status())) {
                markDetail(detail, TransactionItemStatus.FAILED, failureReason(result.errorCode(), "MEMBER_POINT_FAILED"), now);
                failedCount++;
                continue;
            }
            markDetail(detail, TransactionItemStatus.UNKNOWN, failureReason(result.errorCode(), "MEMBER_POINT_STATUS_UNKNOWN"), now);
            failedCount++;
        }

        marketMapper.settleLoserPredictions(preparation.marketId(), preparation.resultOptionId(), now);
        if (failedCount == 0) {
            marketMapper.completeMarketSettlement(preparation.settlementId(), now);
            marketMapper.completeMarket(preparation.marketId(), now);
            createReputationUpdateTasksForSettledMarket(preparation.marketId(), now);
            return preparation.toResponse(
                    MarketStatus.SETTLED,
                    SettlementStatus.COMPLETED,
                    successCount,
                    failedCount
            );
        }

        marketMapper.touchMarketSettlement(preparation.settlementId(), now);
        return preparation.toResponse(
                MarketStatus.SETTLEMENT_IN_PROGRESS,
                SettlementStatus.IN_PROGRESS,
                successCount,
                failedCount
        );
    }

    @Transactional
    public SettleMarketResponse applySettlementUnknown(SettlementPreparation preparation, String failReason) {
        LocalDateTime now = LocalDateTime.now();
        for (MarketSettlementDetail detail : preparation.winnerDetails()) {
            markDetail(detail, TransactionItemStatus.UNKNOWN, failReason, now);
        }
        marketMapper.settleLoserPredictions(preparation.marketId(), preparation.resultOptionId(), now);
        marketMapper.touchMarketSettlement(preparation.settlementId(), now);
        return preparation.toResponse(
                MarketStatus.SETTLEMENT_IN_PROGRESS,
                SettlementStatus.IN_PROGRESS,
                0,
                preparation.winnerDetails().size()
        );
    }

    @Transactional(readOnly = true)
    public List<Long> selectMarketIdsForSettlementRetry(int limit) {
        return marketMapper.selectMarketIdsForSettlementRetry(pendingThreshold(LocalDateTime.now()), limit);
    }

    @Transactional
    public SettlementRetryPreparation prepareSettlementRetry(long marketId, boolean publishPointRequests) {
        LocalDateTime now = LocalDateTime.now();
        Market market = marketMapper.lockMarketById(marketId);
        if (market == null) {
            throw new CustomException(MarketErrorCode.MARKET_NOT_FOUND);
        }
        if (market.getStatus() == MarketStatus.SETTLED) {
            throw new CustomException(MarketErrorCode.MARKET_ALREADY_SETTLED);
        }
        if (market.getStatus() != MarketStatus.SETTLEMENT_IN_PROGRESS) {
            throw new CustomException(MarketErrorCode.MARKET_INVALID_STATUS);
        }

        MarketSettlement settlement = marketMapper.selectMarketSettlementByMarketId(marketId);
        if (settlement == null) {
            throw new CustomException(MarketErrorCode.MARKET_INVALID_SETTLEMENT_DATA);
        }
        if (settlement.getStatus() == SettlementStatus.COMPLETED) {
            throw new CustomException(MarketErrorCode.MARKET_ALREADY_SETTLED);
        }
        if (settlement.getStatus() != SettlementStatus.IN_PROGRESS) {
            throw new CustomException(MarketErrorCode.MARKET_INVALID_SETTLEMENT_DATA);
        }

        // 정산 준비 커밋 후 Member-Point 호출 전에 중단된 경우 패자가 CONFIRMED로 남아 있다.
        // CONFIRMED 패자만 갱신하므로 정상 흐름에서는 변화가 없다 (ASYNC_POINT_FLOW.md §14 S3).
        marketMapper.settleLoserPredictions(marketId, settlement.getResultOptionId(), now);

        List<MarketSettlementDetail> retryDetails = marketMapper.selectRetryableSettlementDetails(
                settlement.getId(),
                pendingThreshold(now)
        );
        long nonSuccessCount = marketMapper.countNonSuccessSettlementDetails(settlement.getId());
        if (retryDetails.isEmpty()) {
            if (nonSuccessCount == 0) {
                marketMapper.completeMarketSettlement(settlement.getId(), now);
                marketMapper.completeMarket(marketId, now);
                createReputationUpdateTasksForSettledMarket(marketId, now);
                return toRetryPreparation(marketId, settlement, List.of(), true);
            }
            throw new CustomException(MarketErrorCode.MARKET_INVALID_SETTLEMENT_DATA);
        }

        if (publishPointRequests) {
            // async 수동 재시도(R5): 같은 item 멱등성 키 + 새 eventId로 outbox에 다시 기록한다.
            for (MarketSettlementDetail detail : retryDetails) {
                markRetryDetail(detail, TransactionItemStatus.PENDING, null, now);
            }
            pointRequestOutboxWriter.appendSettlementPayoutRequests(
                    marketId,
                    retryDetails,
                    MarketSettlementService.RETRY_REASON,
                    now
            );
            marketMapper.touchMarketSettlement(settlement.getId(), now);
        }
        return toRetryPreparation(marketId, settlement, retryDetails, false);
    }

    /**
     * Member-Point 결과 이벤트(memberpoint.point-results)를 정산 detail에 반영한다.
     * SUCCESS는 종결 상태이므로 SQL 가드로 다시 바뀌지 않는다 (ASYNC_POINT_FLOW.md §14).
     */
    @Transactional
    public PointResultApplyOutcome applyAsyncSettlementResult(
            long marketId,
            String idempotencyKey,
            boolean credited,
            String errorCode
    ) {
        // 락 순서: market → detail → market_settlement (prepareSettlement / prepareSettlementRetry와 동일)
        if (marketMapper.lockMarketById(marketId) == null) {
            return PointResultApplyOutcome.DETAIL_NOT_FOUND;
        }
        AppliedPointResult applied = applySettlementResultItem(idempotencyKey, credited, errorCode);
        if (applied.parentId() != null) {
            completeSettlementIfDone(marketId, applied.parentId());
        }
        return applied.outcome();
    }

    /**
     * 호출자의 트랜잭션 안에서 정산 결과 1건을 detail에 반영한다.
     * market row 잠금과 완료 판정은 호출자가 한다 (배치 반영, ASYNC_POINT_FLOW.md §16.1).
     */
    public AppliedPointResult applySettlementResultItem(String idempotencyKey, boolean credited, String errorCode) {
        LocalDateTime now = LocalDateTime.now();
        MarketSettlementDetail detail = marketMapper.selectSettlementDetailByIdempotencyKeyForUpdate(idempotencyKey);
        if (detail == null) {
            return AppliedPointResult.detailNotFound();
        }
        int updatedRows = credited
                ? marketMapper.updateRetrySettlementDetailStatus(detail.getId(), TransactionItemStatus.SUCCESS, null, now)
                : marketMapper.updateRetrySettlementDetailStatus(
                        detail.getId(),
                        TransactionItemStatus.FAILED,
                        failureReason(errorCode, "MEMBER_POINT_FAILED"),
                        now
                );
        if (credited && updatedRows == 1) {
            marketMapper.settlePrediction(detail.getPredictionId(), detail.getSettledAmount(), now);
        }
        return new AppliedPointResult(
                updatedRows == 1 ? PointResultApplyOutcome.APPLIED : PointResultApplyOutcome.SKIPPED,
                detail.getSettlementId()
        );
    }

    /** 호출자의 트랜잭션 안에서 정산 완료 여부를 판정하고, 완료면 정산·마켓 완료와 reputation task를 만든다. */
    public void completeSettlementIfDone(long marketId, long settlementId) {
        LocalDateTime now = LocalDateTime.now();
        if (marketMapper.countNonSuccessSettlementDetails(settlementId) == 0) {
            marketMapper.completeMarketSettlement(settlementId, now);
            marketMapper.completeMarket(marketId, now);
            createReputationUpdateTasksForSettledMarket(marketId, now);
        } else {
            marketMapper.touchMarketSettlement(settlementId, now);
        }
    }

    @Transactional
    public SettleMarketResponse applySettlementRetryResult(
            SettlementRetryPreparation preparation,
            MemberPointSettlementBatchResponse response
    ) {
        LocalDateTime now = LocalDateTime.now();
        Map<Long, MemberPointSettlementItemResult> resultsByPredictionId = response.results().stream()
                .collect(Collectors.toMap(
                        MemberPointSettlementItemResult::predictionId,
                        Function.identity(),
                        (first, ignored) -> first
                ));

        int successCount = 0;
        for (MarketSettlementDetail detail : preparation.retryDetails()) {
            MemberPointSettlementItemResult result = resultsByPredictionId.get(detail.getPredictionId());
            if (result == null) {
                markRetryDetail(detail, TransactionItemStatus.UNKNOWN, "MEMBER_POINT_RESULT_MISSING", now);
                continue;
            }
            if (isSuccess(result.status())) {
                markRetryDetail(detail, TransactionItemStatus.SUCCESS, null, now);
                marketMapper.settlePrediction(detail.getPredictionId(), detail.getSettledAmount(), now);
                successCount++;
                continue;
            }
            if (isFailed(result.status())) {
                markRetryDetail(detail, TransactionItemStatus.FAILED, failureReason(result.errorCode(), "MEMBER_POINT_FAILED"), now);
                continue;
            }
            markRetryDetail(detail, TransactionItemStatus.UNKNOWN, failureReason(result.errorCode(), "MEMBER_POINT_STATUS_UNKNOWN"), now);
        }

        long remainingNonSuccessCount = marketMapper.countNonSuccessSettlementDetails(preparation.settlementId());
        if (remainingNonSuccessCount == 0) {
            marketMapper.completeMarketSettlement(preparation.settlementId(), now);
            marketMapper.completeMarket(preparation.marketId(), now);
            createReputationUpdateTasksForSettledMarket(preparation.marketId(), now);
            return preparation.toResponse(
                    MarketStatus.SETTLED,
                    SettlementStatus.COMPLETED,
                    successCount,
                    0
            );
        }

        marketMapper.touchMarketSettlement(preparation.settlementId(), now);
        int remainingRetryableCount = Math.toIntExact(marketMapper.countRetryableSettlementDetails(
                preparation.settlementId(),
                pendingThreshold(now)
        ));
        return preparation.toResponse(
                MarketStatus.SETTLEMENT_IN_PROGRESS,
                SettlementStatus.IN_PROGRESS,
                successCount,
                remainingRetryableCount
        );
    }

    @Transactional
    public SettleMarketResponse applySettlementRetryUnknown(SettlementRetryPreparation preparation, String failReason) {
        LocalDateTime now = LocalDateTime.now();
        for (MarketSettlementDetail detail : preparation.retryDetails()) {
            markRetryDetail(detail, TransactionItemStatus.UNKNOWN, failReason, now);
        }
        marketMapper.touchMarketSettlement(preparation.settlementId(), now);
        int remainingRetryableCount = Math.toIntExact(marketMapper.countRetryableSettlementDetails(
                preparation.settlementId(),
                pendingThreshold(now)
        ));
        return preparation.toResponse(
                MarketStatus.SETTLEMENT_IN_PROGRESS,
                SettlementStatus.IN_PROGRESS,
                0,
                remainingRetryableCount
        );
    }

    private void throwSettlementStartError(long marketId) {
        Market market = marketMapper.selectMarketById(marketId);
        if (market == null) {
            throw new CustomException(MarketErrorCode.MARKET_NOT_FOUND);
        }
        if (market.getStatus() == MarketStatus.SETTLED) {
            throw new CustomException(MarketErrorCode.MARKET_ALREADY_SETTLED);
        }
        throw new CustomException(MarketErrorCode.MARKET_INVALID_STATUS);
    }

    private void createReputationUpdateTasksForSettledMarket(long marketId, LocalDateTime now) {
        int insertedCount = marketMapper.insertReputationUpdateTasksForSettledPredictions(marketId, now);
        log.info("Created market reputation update tasks. marketId={}, insertedCount={}", marketId, insertedCount);
    }

    private void validateSettlementData(Market market) {
        if (market.getResultOptionId() == null || market.getFeeRate() == null) {
            throw new CustomException(MarketErrorCode.MARKET_INVALID_SETTLEMENT_DATA);
        }
        MarketOption resultOption = marketMapper.selectOptionById(market.getResultOptionId());
        if (resultOption == null || !market.getId().equals(resultOption.getMarketId())) {
            throw new CustomException(MarketErrorCode.MARKET_INVALID_SETTLEMENT_DATA);
        }
    }

    private MarketSettlement createSettlement(
            Market market,
            BigDecimal totalPool,
            BigDecimal feeAmount,
            BigDecimal settlementPool,
            BigDecimal winningContractQuantity,
            BigDecimal rewardPerContract,
            LocalDateTime now
    ) {
        MarketSettlement settlement = new MarketSettlement();
        settlement.setMarketId(market.getId());
        settlement.setResultOptionId(market.getResultOptionId());
        settlement.setTotalPool(totalPool);
        settlement.setFeeRate(market.getFeeRate());
        settlement.setFeeAmount(feeAmount);
        settlement.setSettlementPool(settlementPool);
        settlement.setWinningContractQuantity(winningContractQuantity);
        settlement.setPayoutPerContract(rewardPerContract);
        settlement.setBurnedPointAmount(ZERO_AMOUNT);
        settlement.setStatus(SettlementStatus.IN_PROGRESS);
        settlement.setCreatedAt(now);
        settlement.setUpdatedAt(now);
        return settlement;
    }

    private MarketSettlementDetail toPendingDetail(
            long marketId,
            Long settlementId,
            MarketPrediction prediction,
            BigDecimal rewardPerContract,
            LocalDateTime now
    ) {
        BigDecimal settledAmount = floorAmount(
                prediction.getPointAmount().add(contractQuantity(prediction).multiply(rewardPerContract))
        );
        MarketSettlementDetail detail = new MarketSettlementDetail();
        detail.setSettlementId(settlementId);
        detail.setPredictionId(prediction.getId());
        detail.setMemberId(prediction.getMemberId());
        detail.setOriginalPointAmount(prediction.getPointAmount());
        detail.setContractQuantity(contractQuantity(prediction));
        detail.setPayoutPerContract(rewardPerContract);
        detail.setSettledAmount(settledAmount);
        detail.setProfitAmount(floorAmount(settledAmount.subtract(prediction.getPointAmount())));
        detail.setStatus(TransactionItemStatus.PENDING);
        detail.setIdempotencyKey(rewardIdempotencyKey(marketId, prediction));
        detail.setCreatedAt(now);
        detail.setUpdatedAt(now);
        return detail;
    }

    private BigDecimal contractQuantity(MarketPrediction prediction) {
        if (prediction.getContractQuantity() == null) {
            throw new CustomException(MarketErrorCode.MARKET_INVALID_SETTLEMENT_DATA);
        }
        return prediction.getContractQuantity().setScale(QUANTITY_SCALE, RoundingMode.DOWN);
    }

    private String rewardIdempotencyKey(long marketId, MarketPrediction prediction) {
        return "MARKET_SETTLEMENT_REWARD:market:%d:prediction:%d:member:%d"
                .formatted(marketId, prediction.getId(), prediction.getMemberId());
    }

    private void markDetail(
            MarketSettlementDetail detail,
            TransactionItemStatus status,
            String failReason,
            LocalDateTime now
    ) {
        marketMapper.updateSettlementDetailStatus(detail.getId(), status, failReason, now);
    }

    private void markRetryDetail(
            MarketSettlementDetail detail,
            TransactionItemStatus status,
            String failReason,
            LocalDateTime now
    ) {
        marketMapper.updateRetrySettlementDetailStatus(detail.getId(), status, failReason, now);
    }

    private SettlementRetryPreparation toRetryPreparation(
            long marketId,
            MarketSettlement settlement,
            List<MarketSettlementDetail> retryDetails,
            boolean completed
    ) {
        return new SettlementRetryPreparation(
                marketId,
                settlement.getId(),
                settlement.getResultOptionId(),
                settlement.getTotalPool(),
                settlement.getFeeAmount(),
                settlement.getSettlementPool(),
                settlement.getWinningContractQuantity(),
                settlement.getPayoutPerContract(),
                settlement.getBurnedPointAmount(),
                Math.toIntExact(marketMapper.countSettlementDetailsBySettlementId(settlement.getId())),
                Math.toIntExact(marketMapper.countSettledLoserPredictions(marketId, settlement.getResultOptionId())),
                retryDetails,
                completed
        );
    }

    private boolean isSuccess(MemberPointSettlementItemStatus status) {
        return status == MemberPointSettlementItemStatus.PROCESSED
                || status == MemberPointSettlementItemStatus.ALREADY_PROCESSED;
    }

    private boolean isFailed(MemberPointSettlementItemStatus status) {
        return status == MemberPointSettlementItemStatus.FAILED;
    }

    private BigDecimal floorAmount(BigDecimal amount) {
        return amount.setScale(AMOUNT_SCALE, RoundingMode.DOWN);
    }

    static String failureReason(String reason, String fallback) {
        String value = reason == null || reason.isBlank() ? fallback : reason;
        return value.length() <= 255 ? value : value.substring(0, 255);
    }

    /** 이 시각 이전에 갱신된 PENDING detail은 정체로 보고 재시도 대상에 포함한다 (환불과 같은 기준, §14 S3). */
    private LocalDateTime pendingThreshold(LocalDateTime now) {
        return now.minusMinutes(RETRY_PENDING_THRESHOLD_MINUTES);
    }
}
