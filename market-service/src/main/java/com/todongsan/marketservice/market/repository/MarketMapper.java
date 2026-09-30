package com.todongsan.marketservice.market.repository;

import com.todongsan.marketservice.market.entity.Market;
import com.todongsan.marketservice.market.entity.MarketOption;
import com.todongsan.marketservice.market.entity.MarketPrediction;
import com.todongsan.marketservice.market.entity.MarketPriceHistory;
import com.todongsan.marketservice.market.entity.MarketRefundDetail;
import com.todongsan.marketservice.market.entity.MarketSettlement;
import com.todongsan.marketservice.market.entity.MarketSettlementDetail;
import com.todongsan.marketservice.market.entity.MarketVoid;
import com.todongsan.marketservice.market.type.AdminMarketProblemType;
import com.todongsan.marketservice.market.type.MarketDisplayStatus;
import com.todongsan.marketservice.market.type.MarketSort;
import com.todongsan.marketservice.market.type.MarketStatus;
import com.todongsan.marketservice.market.type.PredictionStatus;
import com.todongsan.marketservice.market.type.RefundStatus;
import com.todongsan.marketservice.market.type.TransactionItemStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface MarketMapper {

    List<Market> selectMarkets(
            @Param("status") MarketStatus status,
            @Param("keyword") String keyword,
            @Param("displayStatus") MarketDisplayStatus displayStatus,
            @Param("sort") MarketSort sort,
            @Param("now") LocalDateTime now,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    long countMarkets(
            @Param("status") MarketStatus status,
            @Param("keyword") String keyword,
            @Param("displayStatus") MarketDisplayStatus displayStatus,
            @Param("now") LocalDateTime now
    );

    Market selectMarketById(@Param("marketId") long marketId);

    Market selectMarketBasicInfo(@Param("marketId") long marketId);

    Market selectAdminMarketById(@Param("marketId") long marketId);

    List<MarketOption> selectOptionsByMarketId(@Param("marketId") long marketId);

    List<MarketOption> selectOptionsByMarketIds(@Param("marketIds") List<Long> marketIds);

    List<MarketPriceHistoryRow> selectPriceHistory(
            @Param("marketId") long marketId,
            @Param("optionId") Long optionId,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    long countPriceHistory(
            @Param("marketId") long marketId,
            @Param("optionId") Long optionId
    );

    MarketInsightSummaryRow selectInsightMarketSummary(@Param("marketId") long marketId);

    List<MarketInsightOptionStatisticsRow> selectInsightOptionStatistics(@Param("marketId") long marketId);

    long countInsightPredictions(@Param("marketId") long marketId);

    List<MarketInsightPredictionRow> selectInsightPredictions(
            @Param("marketId") long marketId,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    int insertReputationUpdateTask(MarketReputationUpdateRow row);

    int insertReputationUpdateTasksForSettledPredictions(
            @Param("marketId") long marketId,
            @Param("now") LocalDateTime now
    );

    MarketReputationUpdateRow selectReputationUpdateByPredictionId(@Param("predictionId") long predictionId);

    List<MarketReputationUpdateRow> selectPendingOrUnknownReputationUpdates(@Param("limit") int limit);

    int markReputationUpdateSuccess(
            @Param("id") long id,
            @Param("now") LocalDateTime now
    );

    int markReputationUpdateFailed(
            @Param("id") long id,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage,
            @Param("now") LocalDateTime now
    );

    int markReputationUpdateUnknown(
            @Param("id") long id,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage,
            @Param("now") LocalDateTime now
    );

    void insertMarket(MarketInsertRow market);

    void insertMarketOptions(@Param("options") List<MarketOptionInsertRow> options);

    int activatePendingMarket(
            @Param("marketId") long marketId,
            @Param("updatedAt") java.time.LocalDateTime updatedAt
    );

    long countUnresolvedPredictionsForResult(@Param("marketId") long marketId);

    void clearResultOptions(
            @Param("marketId") long marketId,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    int markResultOption(
            @Param("marketId") long marketId,
            @Param("resultOptionId") long resultOptionId,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    int updateMarketResult(
            @Param("marketId") long marketId,
            @Param("resultOptionId") long resultOptionId,
            @Param("resultValue") BigDecimal resultValue,
            @Param("resultText") String resultText,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    MarketOption selectOptionById(@Param("optionId") long optionId);

    MarketPrediction selectPredictionByMarketIdAndMemberId(
            @Param("marketId") long marketId,
            @Param("memberId") long memberId
    );

    MyMarketPredictionRow selectPredictionForPayoutEstimate(
            @Param("marketId") long marketId,
            @Param("memberId") long memberId
    );

    long countPredictionsByMemberId(MyMarketPredictionSearchCondition condition);

    List<MyMarketPredictionRow> selectPredictionsByMemberId(MyMarketPredictionSearchCondition condition);

    MarketPrediction selectPredictionById(@Param("predictionId") long predictionId);

    MarketPrediction lockPredictionById(@Param("predictionId") long predictionId);

    List<MarketPrediction> selectPredictionsForSpendReconciliation(
            @Param("pendingThreshold") LocalDateTime pendingThreshold,
            @Param("limit") int limit
    );

    void insertPrediction(MarketPrediction prediction);

    int retryFailedPrediction(
            @Param("predictionId") long predictionId,
            @Param("optionId") long optionId,
            @Param("pointAmount") BigDecimal pointAmount,
            @Param("pointSpendIdempotencyKey") String pointSpendIdempotencyKey,
            @Param("attemptNo") int attemptNo,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    Market lockMarketById(@Param("marketId") long marketId);

    List<MarketOption> lockOptionsByMarketId(@Param("marketId") long marketId);

    void updateMarketTotalPool(
            @Param("marketId") long marketId,
            @Param("pointAmount") BigDecimal pointAmount,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    void updateMarketOptionPoolsAndPrice(MarketOption option);

    void insertPriceHistoryRows(@Param("histories") List<MarketPriceHistory> histories);

    int updatePredictionConfirmed(
            @Param("predictionId") long predictionId,
            @Param("priceSnapshot") BigDecimal priceSnapshot,
            @Param("contractQuantity") BigDecimal contractQuantity,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    int updatePredictionConfirmedForReconciliation(
            @Param("predictionId") long predictionId,
            @Param("priceSnapshot") BigDecimal priceSnapshot,
            @Param("contractQuantity") BigDecimal contractQuantity,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    int updatePendingPredictionStatus(
            @Param("predictionId") long predictionId,
            @Param("status") PredictionStatus status,
            @Param("failReason") String failReason,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    int updatePredictionFailedForReconciliation(
            @Param("predictionId") long predictionId,
            @Param("failReason") String failReason,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    int updatePredictionUnknownFromPending(
            @Param("predictionId") long predictionId,
            @Param("failReason") String failReason,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    int startSettlement(
            @Param("marketId") long marketId,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    List<MarketPrediction> selectConfirmedPredictionsForSettlement(@Param("marketId") long marketId);

    void insertMarketSettlement(MarketSettlement settlement);

    void insertMarketSettlementDetails(@Param("details") List<MarketSettlementDetail> details);

    MarketSettlement selectMarketSettlementByMarketId(@Param("marketId") long marketId);

    MarketSettlement selectLatestMarketSettlementByMarketIdForRead(@Param("marketId") long marketId);

    MarketSettlement selectMarketSettlementByIdForRead(@Param("settlementId") long settlementId);

    AdminTransactionStatusCountsRow selectSettlementDetailStatusCounts(@Param("settlementId") long settlementId);

    List<AdminSettlementDetailRow> selectSettlementDetailsForAdmin(
            @Param("settlementId") long settlementId,
            @Param("status") TransactionItemStatus status,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    long countSettlementDetailsForAdmin(
            @Param("settlementId") long settlementId,
            @Param("status") TransactionItemStatus status
    );

    List<MarketSettlementDetail> selectSettlementDetailsBySettlementId(@Param("settlementId") long settlementId);

    List<MarketSettlementDetail> selectRetryableSettlementDetails(@Param("settlementId") long settlementId);

    List<Long> selectMarketIdsForSettlementRetry(@Param("limit") int limit);

    long countSettlementDetailsBySettlementId(@Param("settlementId") long settlementId);

    long countNonSuccessSettlementDetails(@Param("settlementId") long settlementId);

    long countRetryableSettlementDetails(@Param("settlementId") long settlementId);

    long countSettledLoserPredictions(
            @Param("marketId") long marketId,
            @Param("resultOptionId") long resultOptionId
    );

    int updateMarketSettlementAmounts(
            @Param("marketId") long marketId,
            @Param("feeAmount") BigDecimal feeAmount,
            @Param("settlementPool") BigDecimal settlementPool,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    int updateSettlementDetailStatus(
            @Param("detailId") long detailId,
            @Param("status") TransactionItemStatus status,
            @Param("failReason") String failReason,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    int updateRetrySettlementDetailStatus(
            @Param("detailId") long detailId,
            @Param("status") TransactionItemStatus status,
            @Param("failReason") String failReason,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    int settlePrediction(
            @Param("predictionId") long predictionId,
            @Param("settledAmount") BigDecimal settledAmount,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    int settleLoserPredictions(
            @Param("marketId") long marketId,
            @Param("resultOptionId") long resultOptionId,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    int settleAllConfirmedPredictionsZero(
            @Param("marketId") long marketId,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    int completeMarketSettlement(
            @Param("settlementId") long settlementId,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    int touchMarketSettlement(
            @Param("settlementId") long settlementId,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    int completeMarket(
            @Param("marketId") long marketId,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    long countUnresolvedPredictionsForVoid(@Param("marketId") long marketId);

    long countConfirmedPredictionsForRefund(@Param("marketId") long marketId);

    List<MarketPrediction> selectConfirmedPredictionsForRefund(@Param("marketId") long marketId);

    MarketVoid selectMarketVoidByMarketId(@Param("marketId") long marketId);

    MarketVoid selectMarketVoidByMarketIdForRead(@Param("marketId") long marketId);

    MarketVoid selectMarketVoidByIdForRead(@Param("voidId") long voidId);

    AdminTransactionStatusCountsRow selectRefundDetailStatusCounts(@Param("voidId") long voidId);

    List<AdminRefundDetailRow> selectRefundDetailsForAdmin(
            @Param("voidId") long voidId,
            @Param("status") TransactionItemStatus status,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    long countRefundDetailsForAdmin(
            @Param("voidId") long voidId,
            @Param("status") TransactionItemStatus status
    );

    void insertMarketVoid(MarketVoid marketVoid);

    int updateMarketStatusToVoided(
            @Param("marketId") long marketId,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    long countRefundDetailsByVoidId(@Param("marketVoidId") long marketVoidId);

    void insertMarketRefundDetails(@Param("details") List<MarketRefundDetail> details);

    List<MarketRefundDetail> selectPendingRefundDetailsByVoidId(@Param("marketVoidId") long marketVoidId);

    List<MarketRefundDetail> selectRetryableRefundDetails(
            @Param("marketVoidId") long marketVoidId,
            @Param("pendingThreshold") LocalDateTime pendingThreshold
    );

    List<Long> selectMarketIdsForRefundRetry(
            @Param("pendingThreshold") LocalDateTime pendingThreshold,
            @Param("limit") int limit
    );

    long countNonSuccessRefundDetails(@Param("marketVoidId") long marketVoidId);

    int updatePredictionToRefundPending(
            @Param("predictionId") long predictionId,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    int updateRefundDetailStatus(
            @Param("detailId") long detailId,
            @Param("status") TransactionItemStatus status,
            @Param("failReason") String failReason,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    int updateRetryRefundDetailStatus(
            @Param("detailId") long detailId,
            @Param("status") TransactionItemStatus status,
            @Param("failReason") String failReason,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    int markPredictionRefunded(
            @Param("predictionId") long predictionId,
            @Param("refundAmount") BigDecimal refundAmount,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    int markPredictionRefundUnknown(
            @Param("predictionId") long predictionId,
            @Param("failReason") String failReason,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    int markPredictionRefundPending(
            @Param("predictionId") long predictionId,
            @Param("failReason") String failReason,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    int updateMarketVoidRefundStatus(
            @Param("marketVoidId") long marketVoidId,
            @Param("refundStatus") RefundStatus refundStatus,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    List<AdminMarketProblemRow> selectProblemMarketsForAdmin(
            @Param("type") AdminMarketProblemType type,
            @Param("pendingThreshold") LocalDateTime pendingThreshold,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    long countProblemMarketsForAdmin(
            @Param("type") AdminMarketProblemType type,
            @Param("pendingThreshold") LocalDateTime pendingThreshold
    );

    long countDistinctProblemMarketsForAdmin(@Param("pendingThreshold") LocalDateTime pendingThreshold);

    AdminMarketStatusCountsRow selectAdminMarketStatusCounts(@Param("now") LocalDateTime now);
}
