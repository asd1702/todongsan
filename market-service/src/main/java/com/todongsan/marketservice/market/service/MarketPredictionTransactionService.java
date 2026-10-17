package com.todongsan.marketservice.market.service;

import com.todongsan.marketservice.global.exception.CustomException;
import com.todongsan.marketservice.global.exception.errorcode.MarketErrorCode;
import com.todongsan.marketservice.market.dto.request.CreatePredictionRequest;
import com.todongsan.marketservice.market.dto.request.QuoteMarketPredictionRequest;
import com.todongsan.marketservice.market.dto.response.CreatePredictionResponse;
import com.todongsan.marketservice.market.dto.response.QuoteMarketPredictionResponse;
import com.todongsan.marketservice.market.debug.MarketPredictionDeadlockDebug;
import com.todongsan.marketservice.market.entity.Market;
import com.todongsan.marketservice.market.entity.MarketOption;
import com.todongsan.marketservice.market.entity.MarketPrediction;
import com.todongsan.marketservice.market.entity.MarketPriceHistory;
import com.todongsan.marketservice.market.repository.ConfirmedPredictionRow;
import com.todongsan.marketservice.market.repository.MarketMapper;
import com.todongsan.marketservice.market.type.MarketStatus;
import com.todongsan.marketservice.market.type.PredictionStatus;
import com.todongsan.marketservice.market.type.PriceHistoryEventType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MarketPredictionTransactionService {

    private static final int MONEY_SCALE = 2;
    private static final int PRICE_SCALE = 8;
    private static final int QUANTITY_SCALE = 8;
    private static final int RATE_SCALE = 8;
    private static final RoundingMode QUOTE_ROUNDING = RoundingMode.HALF_UP;
    private static final String QUOTE_NOTICE =
            "현재 가격은 실시간으로 변동될 수 있으며, 실제 참여 시점의 가격 기준으로 계약 수량이 확정됩니다.";

    private final MarketMapper marketMapper;
    private final MarketPredictionDeadlockDebug deadlockDebug;

    @Transactional
    public MarketPrediction createPendingPrediction(
            long marketId,
            long memberId,
            String idempotencyKey,
            CreatePredictionRequest request
    ) {
        deadlockDebug.phase("TX1_START", null);
        LocalDateTime now = LocalDateTime.now();
        Market market = getMarket(marketId);
        validateMarketForPrediction(market, now);
        MarketOption option = getMarketOption(marketId, request.getMarketOptionId());
        validatePointAmount(request.getPointAmount());
        deadlockDebug.phase("PREDICTION_EXISTING_LOOKUP_BEFORE", null);
        MarketPrediction prediction = marketMapper.selectPredictionByMarketIdAndMemberId(marketId, memberId);
        deadlockDebug.phase("PREDICTION_EXISTING_LOOKUP_AFTER", prediction == null ? null : prediction.getId());
        if (prediction != null) {
            return retryFailedPrediction(prediction, option, request, idempotencyKey, now);
        }

        prediction = new MarketPrediction();
        prediction.setMarketId(marketId);
        prediction.setOptionId(option.getId());
        prediction.setMemberId(memberId);
        prediction.setPointAmount(request.getPointAmount());
        prediction.setStatus(PredictionStatus.POINT_PENDING);
        prediction.setAttemptNo(1);
        prediction.setPointSpendIdempotencyKey(pointSpendIdempotencyKey(idempotencyKey, prediction.getAttemptNo()));
        prediction.setCreatedAt(now);
        prediction.setUpdatedAt(now);
        deadlockDebug.phase("PREDICTION_INSERT_BEFORE", null);
        marketMapper.insertPrediction(prediction);
        deadlockDebug.phase("PREDICTION_INSERT_AFTER", prediction.getId());
        deadlockDebug.phase("TX1_BODY_END", prediction.getId());
        return prediction;
    }

    private MarketPrediction retryFailedPrediction(
            MarketPrediction prediction,
            MarketOption option,
            CreatePredictionRequest request,
            String idempotencyKey,
            LocalDateTime now
    ) {
        if (prediction.getStatus() != PredictionStatus.FAILED) {
            throw new CustomException(MarketErrorCode.MARKET_ALREADY_PREDICTED);
        }
        int nextAttemptNo = prediction.getAttemptNo() + 1;
        int updatedRows = marketMapper.retryFailedPrediction(
                prediction.getId(),
                option.getId(),
                request.getPointAmount(),
                pointSpendIdempotencyKey(idempotencyKey, nextAttemptNo),
                nextAttemptNo,
                now
        );
        if (updatedRows != 1) {
            throw new CustomException(MarketErrorCode.MARKET_ALREADY_PREDICTED);
        }
        return marketMapper.selectPredictionById(prediction.getId());
    }

    private String pointSpendIdempotencyKey(String idempotencyKey, int attemptNo) {
        return "%s:attempt:%d".formatted(idempotencyKey, attemptNo);
    }

    /**
     * 같은 마켓의 예측 여러 건을 트랜잭션 1개로 확정한다 (PREDICTION_BATCH_CONFIRM.md P4·P5).
     * 마켓·선택지·예측을 한 번씩 잠그고, 받은 순서대로 건별 확정(confirmPrediction)과 같은 규칙을 메모리에서 적용한 뒤
     * 최종 상태만 기록하고 commit 1번. 결과는 입력 순서대로, 건마다 응답 또는 건별 경로가 던졌을 예외다.
     * 확정하지 못한 건은 쓰지 않는다. 기록 건수가 맞지 않으면 예외로 전체를 롤백한다 (호출자가 건별로 다시 처리, P6).
     */
    @Transactional
    public List<ConfirmOutcome> confirmPredictionsInBatch(long marketId, List<Long> predictionIds) {
        List<ConfirmOutcome> outcomes = new ArrayList<>(predictionIds.size());
        Market market = marketMapper.lockMarketById(marketId);
        if (market == null || market.getStatus() != MarketStatus.ACTIVE) {
            CustomException failure = market == null
                    ? new CustomException(MarketErrorCode.MARKET_NOT_FOUND)
                    : priceUpdateConflict();
            predictionIds.forEach(id -> outcomes.add(ConfirmOutcome.failed(failure)));
            return outcomes;
        }
        List<MarketOption> options = marketMapper.lockOptionsByMarketId(marketId);
        Map<Long, MarketPrediction> predictions = marketMapper.lockPredictionsByIdsInOrder(
                        predictionIds.stream().distinct().sorted().toList()).stream()
                .collect(Collectors.toMap(MarketPrediction::getId, prediction -> prediction));

        LocalDateTime now = LocalDateTime.now();
        BigDecimal totalAmount = BigDecimal.ZERO;
        List<MarketPriceHistory> histories = new ArrayList<>();
        List<ConfirmedPredictionRow> rows = new ArrayList<>();
        for (Long predictionId : predictionIds) {
            MarketPrediction prediction = predictions.get(predictionId);
            if (prediction == null
                    || hasMissingReconciliationData(prediction)
                    || prediction.getMarketId() != marketId
                    || prediction.getStatus() != PredictionStatus.POINT_PENDING) {
                outcomes.add(ConfirmOutcome.failed(priceUpdateConflict()));
                continue;
            }
            AppliedConfirmation applied = applyConfirmation(options, prediction, now);
            if (applied.rejection() != null) {
                outcomes.add(ConfirmOutcome.failed(applied.rejection() == ConfirmRejection.OPTION_NOT_FOUND
                        ? new CustomException(MarketErrorCode.MARKET_OPTION_NOT_FOUND)
                        : priceUpdateConflict()));
                continue;
            }
            // 같은 예측이 묶음에 두 번 오면 두 번째는 건별 경로처럼 이미 확정된 것으로 본다
            prediction.setStatus(PredictionStatus.CONFIRMED);
            totalAmount = totalAmount.add(prediction.getPointAmount());
            histories.addAll(applied.histories());
            rows.add(new ConfirmedPredictionRow(predictionId, applied.priceSnapshot(), applied.contractQuantity()));
            outcomes.add(ConfirmOutcome.confirmed(new CreatePredictionResponse(
                    predictionId,
                    marketId,
                    applied.selectedOption().getId(),
                    prediction.getPointAmount(),
                    applied.priceSnapshot(),
                    applied.contractQuantity(),
                    PredictionStatus.CONFIRMED
            )));
        }

        if (!rows.isEmpty()) {
            marketMapper.updateMarketTotalPool(marketId, totalAmount, now);
            for (MarketOption option : options) {
                marketMapper.updateMarketOptionPoolsAndPrice(option);
            }
            marketMapper.insertPriceHistoryRows(histories);
            int updated = marketMapper.updatePredictionsConfirmed(rows, now);
            if (updated != rows.size()) {
                throw new IllegalStateException("Batch confirm updated " + updated + " of " + rows.size() + " predictions");
            }
        }
        return outcomes;
    }

    /** 묶음 확정의 건별 결과: 응답 또는 건별 경로가 던졌을 예외 */
    public record ConfirmOutcome(CreatePredictionResponse response, RuntimeException failure) {

        static ConfirmOutcome confirmed(CreatePredictionResponse response) {
            return new ConfirmOutcome(response, null);
        }

        static ConfirmOutcome failed(RuntimeException failure) {
            return new ConfirmOutcome(null, failure);
        }
    }

    @Transactional
    public CreatePredictionResponse confirmPrediction(long predictionId) {
        PredictionConfirmationResult result = confirmPredictionInternal(
                predictionId,
                Set.of(PredictionStatus.POINT_PENDING),
                Set.of(MarketStatus.ACTIVE),
                false
        );
        if (!result.confirmed()) {
            throw priceUpdateConflict();
        }
        return result.response();
    }

    @Transactional(readOnly = true)
    public List<MarketPrediction> selectPredictionsForSpendReconciliation(
            LocalDateTime pendingThreshold,
            int limit
    ) {
        return marketMapper.selectPredictionsForSpendReconciliation(pendingThreshold, limit);
    }

    @Transactional
    public boolean confirmPredictionForReconciliation(long predictionId) {
        return confirmPredictionInternal(
                predictionId,
                Set.of(PredictionStatus.POINT_PENDING, PredictionStatus.POINT_UNKNOWN),
                Set.of(MarketStatus.ACTIVE, MarketStatus.DATA_PENDING),
                true
        ).confirmed();
    }

    @Transactional
    public MarketPrediction markPredictionFailed(long predictionId, String failReason) {
        return updatePendingPredictionStatus(predictionId, PredictionStatus.FAILED, failReason);
    }

    @Transactional
    public MarketPrediction markPredictionUnknown(long predictionId, String failReason) {
        return updatePendingPredictionStatus(predictionId, PredictionStatus.POINT_UNKNOWN, failReason);
    }

    @Transactional
    public boolean failPredictionForReconciliation(long predictionId, String failReason) {
        return marketMapper.updatePredictionFailedForReconciliation(
                predictionId,
                failReason,
                LocalDateTime.now()
        ) == 1;
    }

    @Transactional
    public ReconciliationStatusUpdateResult markPredictionUnknownForReconciliation(
            long predictionId,
            String failReason
    ) {
        int updatedRows = marketMapper.updatePredictionUnknownFromPending(
                predictionId,
                failReason,
                LocalDateTime.now()
        );
        if (updatedRows == 1) {
            return ReconciliationStatusUpdateResult.UPDATED;
        }
        MarketPrediction prediction = marketMapper.selectPredictionById(predictionId);
        if (prediction != null && prediction.getStatus() == PredictionStatus.POINT_UNKNOWN) {
            return ReconciliationStatusUpdateResult.ALREADY_TARGET_STATUS;
        }
        return ReconciliationStatusUpdateResult.SKIPPED;
    }

    private MarketPrediction updatePendingPredictionStatus(
            long predictionId,
            PredictionStatus status,
            String failReason
    ) {
        int updatedRows = marketMapper.updatePendingPredictionStatus(
                predictionId,
                status,
                failReason,
                LocalDateTime.now()
        );
        MarketPrediction prediction = marketMapper.selectPredictionById(predictionId);
        if (prediction == null || (updatedRows != 1 && prediction.getStatus() != status)) {
            throw priceUpdateConflict();
        }
        return prediction;
    }

    private Market getMarket(long marketId) {
        Market market = marketMapper.selectMarketById(marketId);
        if (market == null) {
            throw new CustomException(MarketErrorCode.MARKET_NOT_FOUND);
        }
        return market;
    }

    private void validateMarketForPrediction(Market market, LocalDateTime now) {
        if (market.getStatus() != MarketStatus.ACTIVE) {
            throw new CustomException(MarketErrorCode.MARKET_NOT_ACTIVE);
        }
        if (!market.getCloseAt().isAfter(now)) {
            throw new CustomException(MarketErrorCode.MARKET_CLOSED);
        }
    }

    private MarketOption getMarketOption(long marketId, Long optionId) {
        if (optionId == null) {
            throw new CustomException(MarketErrorCode.MARKET_OPTION_NOT_FOUND);
        }
        MarketOption option = marketMapper.selectOptionById(optionId);
        if (option == null || !option.getMarketId().equals(marketId)) {
            throw new CustomException(MarketErrorCode.MARKET_OPTION_NOT_FOUND);
        }
        return option;
    }

    private void validatePointAmount(BigDecimal pointAmount) {
        PredictionPointAmountValidator.validate(pointAmount);
    }

    @Transactional(readOnly = true)
    public QuoteMarketPredictionResponse quotePrediction(
            long marketId,
            QuoteMarketPredictionRequest request
    ) {
        LocalDateTime now = LocalDateTime.now();
        Market market = getMarket(marketId);
        validateMarketForPrediction(market, now);
        BigDecimal pointAmount = PredictionPointAmountValidator.parseAndValidate(request.pointAmount());

        List<MarketOption> options = marketMapper.selectOptionsByMarketId(marketId);
        MarketOption selectedOption = options.stream()
                .filter(option -> option.getId().equals(request.marketOptionId()))
                .findFirst()
                .orElseThrow(() -> new CustomException(MarketErrorCode.MARKET_OPTION_NOT_FOUND));

        BigDecimal currentPrice = requireOptionAmount(selectedOption.getCurrentPrice())
                .setScale(PRICE_SCALE, QUOTE_ROUNDING);
        if (currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new CustomException(MarketErrorCode.MARKET_INVALID_OPTION);
        }

        BigDecimal selectedOptionEffectivePoolBefore = effectivePool(selectedOption)
                .setScale(MONEY_SCALE, QUOTE_ROUNDING);
        BigDecimal totalEffectivePoolBefore = options.stream()
                .map(this::effectivePool)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(MONEY_SCALE, QUOTE_ROUNDING);
        if (totalEffectivePoolBefore.compareTo(BigDecimal.ZERO) <= 0) {
            throw new CustomException(MarketErrorCode.MARKET_INVALID_OPTION);
        }

        BigDecimal estimatedContractQuantity = pointAmount.divide(currentPrice, QUANTITY_SCALE, QUOTE_ROUNDING);
        BigDecimal selectedOptionEffectivePoolAfter = selectedOptionEffectivePoolBefore
                .add(pointAmount)
                .setScale(MONEY_SCALE, QUOTE_ROUNDING);
        BigDecimal totalEffectivePoolAfter = totalEffectivePoolBefore
                .add(pointAmount)
                .setScale(MONEY_SCALE, QUOTE_ROUNDING);
        BigDecimal estimatedAfterPrice = selectedOptionEffectivePoolAfter
                .divide(totalEffectivePoolAfter, PRICE_SCALE, QUOTE_ROUNDING);
        BigDecimal priceImpactRate = estimatedAfterPrice
                .subtract(currentPrice)
                .divide(currentPrice, RATE_SCALE, QUOTE_ROUNDING)
                .multiply(new BigDecimal("100"))
                .setScale(RATE_SCALE, QUOTE_ROUNDING);

        return new QuoteMarketPredictionResponse(
                market.getId(),
                selectedOption.getId(),
                pointAmount,
                currentPrice,
                estimatedContractQuantity,
                estimatedAfterPrice,
                priceImpactRate,
                selectedOptionEffectivePoolBefore,
                selectedOptionEffectivePoolAfter,
                totalEffectivePoolBefore,
                totalEffectivePoolAfter,
                QUOTE_NOTICE
        );
    }

    private BigDecimal effectivePool(MarketOption option) {
        return requireOptionAmount(option.getRealPoolAmount())
                .add(requireOptionAmount(option.getVirtualPoolAmount()));
    }

    private BigDecimal requireOptionAmount(BigDecimal value) {
        if (value == null) {
            throw new CustomException(MarketErrorCode.MARKET_INVALID_OPTION);
        }
        return value;
    }

    private PredictionConfirmationResult confirmPredictionInternal(
            long predictionId,
            Set<PredictionStatus> allowedPredictionStatuses,
            Set<MarketStatus> allowedMarketStatuses,
            boolean skipOnInvalidState
    ) {
        deadlockDebug.phase("TX2_START", predictionId);
        MarketPrediction initialPrediction = marketMapper.selectPredictionById(predictionId);
        if (initialPrediction == null || hasMissingReconciliationData(initialPrediction)) {
            return confirmationFailure(skipOnInvalidState);
        }

        deadlockDebug.phase("MARKET_LOCK_BEFORE", predictionId);
        Market market = marketMapper.lockMarketById(initialPrediction.getMarketId());
        deadlockDebug.phase("MARKET_LOCK_ACQUIRED", predictionId);
        if (market == null) {
            if (skipOnInvalidState) {
                return PredictionConfirmationResult.skipped();
            }
            throw new CustomException(MarketErrorCode.MARKET_NOT_FOUND);
        }
        if (!allowedMarketStatuses.contains(market.getStatus())) {
            return confirmationFailure(skipOnInvalidState);
        }

        deadlockDebug.phase("OPTIONS_LOCK_BEFORE", predictionId);
        List<MarketOption> options = marketMapper.lockOptionsByMarketId(market.getId());
        deadlockDebug.phase("OPTIONS_LOCK_ACQUIRED", predictionId);
        deadlockDebug.phase("PREDICTION_LOCK_BEFORE", predictionId);
        MarketPrediction prediction = marketMapper.lockPredictionById(predictionId);
        deadlockDebug.phase("PREDICTION_LOCK_ACQUIRED", predictionId);
        if (prediction == null
                || hasMissingReconciliationData(prediction)
                || !allowedPredictionStatuses.contains(prediction.getStatus())) {
            return confirmationFailure(skipOnInvalidState);
        }

        LocalDateTime now = LocalDateTime.now();
        AppliedConfirmation applied = applyConfirmation(options, prediction, now);
        if (applied.rejection() == ConfirmRejection.OPTION_NOT_FOUND) {
            if (skipOnInvalidState) {
                return PredictionConfirmationResult.skipped();
            }
            throw new CustomException(MarketErrorCode.MARKET_OPTION_NOT_FOUND);
        }
        if (applied.rejection() == ConfirmRejection.INVALID) {
            return confirmationFailure(skipOnInvalidState);
        }
        MarketOption selectedOption = applied.selectedOption();
        BigDecimal priceSnapshot = applied.priceSnapshot();
        BigDecimal contractQuantity = applied.contractQuantity();
        deadlockDebug.phase("MARKET_UPDATE_BEFORE", predictionId);
        marketMapper.updateMarketTotalPool(market.getId(), prediction.getPointAmount(), now);
        deadlockDebug.phase("MARKET_UPDATE_AFTER", predictionId);
        for (MarketOption option : options) {
            marketMapper.updateMarketOptionPoolsAndPrice(option);
        }
        deadlockDebug.phase("PRICE_HISTORY_INSERT_BEFORE", predictionId);
        marketMapper.insertPriceHistoryRows(applied.histories());
        deadlockDebug.phase("PRICE_HISTORY_INSERT_AFTER", predictionId);

        deadlockDebug.phase("PREDICTION_CONFIRM_UPDATE_BEFORE", predictionId);
        int updatedRows = skipOnInvalidState
                ? marketMapper.updatePredictionConfirmedForReconciliation(
                        predictionId,
                        priceSnapshot,
                        contractQuantity,
                        now
                )
                : marketMapper.updatePredictionConfirmed(
                        predictionId,
                        priceSnapshot,
                        contractQuantity,
                        now
                );
        deadlockDebug.phase("PREDICTION_CONFIRM_UPDATE_AFTER", predictionId);
        if (updatedRows != 1) {
            return confirmationFailure(skipOnInvalidState);
        }
        deadlockDebug.phase("TX2_BODY_END", predictionId);
        return PredictionConfirmationResult.confirmed(new CreatePredictionResponse(
                predictionId,
                market.getId(),
                selectedOption.getId(),
                prediction.getPointAmount(),
                priceSnapshot,
                contractQuantity,
                PredictionStatus.CONFIRMED
        ));
    }

    /**
     * 잠근 선택지들에 예측 1건을 메모리에서 적용한다 (가격 = 유효 풀 / 전체 유효 풀). 건별 확정과 묶음 확정이 같이 쓴다.
     * 모든 검증을 마친 뒤에만 선택지를 바꾼다. 묶음에서 한 건이 거절돼도 뒤 건의 가격에 흔적이 남지 않게 한다
     * (PREDICTION_BATCH_CONFIRM.md P4).
     */
    private AppliedConfirmation applyConfirmation(List<MarketOption> options, MarketPrediction prediction, LocalDateTime now) {
        MarketOption selectedOption = options.stream()
                .filter(option -> option.getId().equals(prediction.getOptionId()))
                .findFirst()
                .orElse(null);
        if (selectedOption == null) {
            return AppliedConfirmation.rejected(ConfirmRejection.OPTION_NOT_FOUND);
        }
        BigDecimal priceSnapshot = selectedOption.getCurrentPrice().setScale(PRICE_SCALE, RoundingMode.HALF_UP);
        if (priceSnapshot.compareTo(BigDecimal.ZERO) <= 0) {
            return AppliedConfirmation.rejected(ConfirmRejection.INVALID);
        }
        BigDecimal contractQuantity = prediction.getPointAmount()
                .divide(priceSnapshot, PRICE_SCALE, RoundingMode.DOWN);
        BigDecimal totalEffectivePool = options.stream()
                .map(this::effectivePool)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .add(prediction.getPointAmount());
        if (totalEffectivePool.compareTo(BigDecimal.ZERO) <= 0) {
            return AppliedConfirmation.rejected(ConfirmRejection.INVALID);
        }

        Map<Long, OptionSnapshot> snapshots = options.stream()
                .collect(Collectors.toMap(MarketOption::getId, OptionSnapshot::from));
        selectedOption.setRealPoolAmount(selectedOption.getRealPoolAmount().add(prediction.getPointAmount()));
        selectedOption.setTotalContractQuantity(
                selectedOption.getTotalContractQuantity().add(contractQuantity)
        );
        selectedOption.setPredictionCount(selectedOption.getPredictionCount() + 1);
        for (MarketOption option : options) {
            option.setCurrentPrice(effectivePool(option).divide(totalEffectivePool, PRICE_SCALE, RoundingMode.HALF_UP));
            option.setUpdatedAt(now);
        }
        List<MarketPriceHistory> histories = options.stream()
                .map(option -> toPriceHistory(prediction.getId(), option, snapshots.get(option.getId()), now))
                .toList();
        return new AppliedConfirmation(null, selectedOption, priceSnapshot, contractQuantity, histories);
    }

    private enum ConfirmRejection {
        OPTION_NOT_FOUND,
        INVALID
    }

    private record AppliedConfirmation(
            ConfirmRejection rejection,
            MarketOption selectedOption,
            BigDecimal priceSnapshot,
            BigDecimal contractQuantity,
            List<MarketPriceHistory> histories
    ) {
        private static AppliedConfirmation rejected(ConfirmRejection rejection) {
            return new AppliedConfirmation(rejection, null, null, null, List.of());
        }
    }

    private PredictionConfirmationResult confirmationFailure(boolean skipOnInvalidState) {
        if (skipOnInvalidState) {
            return PredictionConfirmationResult.skipped();
        }
        throw priceUpdateConflict();
    }

    private boolean hasMissingReconciliationData(MarketPrediction prediction) {
        return prediction.getMarketId() == null
                || prediction.getOptionId() == null
                || prediction.getMemberId() == null
                || prediction.getPointAmount() == null;
    }

    private MarketPriceHistory toPriceHistory(
            long predictionId,
            MarketOption option,
            OptionSnapshot snapshot,
            LocalDateTime now
    ) {
        MarketPriceHistory history = new MarketPriceHistory();
        history.setMarketId(option.getMarketId());
        history.setOptionId(option.getId());
        history.setPredictionId(predictionId);
        history.setPriceBefore(snapshot.price());
        history.setPriceAfter(option.getCurrentPrice());
        history.setRealPoolBefore(snapshot.realPoolAmount());
        history.setRealPoolAfter(option.getRealPoolAmount());
        history.setContractQuantityBefore(snapshot.contractQuantity());
        history.setContractQuantityAfter(option.getTotalContractQuantity());
        history.setEventType(PriceHistoryEventType.PREDICTION_CONFIRMED);
        history.setCreatedAt(now);
        history.setUpdatedAt(now);
        return history;
    }

    private CustomException priceUpdateConflict() {
        return new CustomException(MarketErrorCode.MARKET_PRICE_UPDATE_CONFLICT);
    }

    private record OptionSnapshot(
            BigDecimal price,
            BigDecimal realPoolAmount,
            BigDecimal contractQuantity
    ) {
        private static OptionSnapshot from(MarketOption option) {
            return new OptionSnapshot(
                    option.getCurrentPrice(),
                    option.getRealPoolAmount(),
                option.getTotalContractQuantity()
            );
        }
    }

    private record PredictionConfirmationResult(
            boolean confirmed,
            CreatePredictionResponse response
    ) {

        private static PredictionConfirmationResult confirmed(CreatePredictionResponse response) {
            return new PredictionConfirmationResult(true, response);
        }

        private static PredictionConfirmationResult skipped() {
            return new PredictionConfirmationResult(false, null);
        }
    }

    public enum ReconciliationStatusUpdateResult {
        UPDATED,
        ALREADY_TARGET_STATUS,
        SKIPPED
    }
}
