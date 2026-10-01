package com.todongsan.marketservice.market.service;

import com.todongsan.marketservice.market.repository.MarketMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Member-Point 결과 이벤트 여러 건을 트랜잭션 1개로 반영한다 (ASYNC_POINT_FLOW.md §16.1).
 *
 * 1. 배치에 등장한 market을 ID 오름차순으로 선잠금 (Q5)
 * 2. 받은 순서대로 순차 반영. detail 없음은 예외가 아닌 결과(DETAIL_NOT_FOUND)로 돌려준다 (Q2)
 * 3. 등장한 무효·정산마다 완료 판정 1회 → COMMIT 1회
 */
@Service
@RequiredArgsConstructor
public class PointResultBatchApplier {

    public static final String MARKET_REFUND_REQUESTED = "MARKET_REFUND_REQUESTED";
    public static final String MARKET_SETTLEMENT_PAYOUT_REQUESTED = "MARKET_SETTLEMENT_PAYOUT_REQUESTED";

    private final MarketMapper marketMapper;
    private final MarketRefundTransactionService refundTransactionService;
    private final MarketSettlementTransactionService settlementTransactionService;

    public record Item(String requestEventType, long marketId, String idempotencyKey, boolean credited, String errorCode) {
    }

    /** 입력 순서와 같은 순서로 결과를 돌려준다. */
    @Transactional
    public List<PointResultApplyOutcome> applyBatch(List<Item> items) {
        List<Long> marketIds = items.stream().map(Item::marketId).distinct().sorted().toList();
        Set<Long> lockedMarketIds = new HashSet<>(marketMapper.lockMarketsByIdsInOrder(marketIds));

        List<PointResultApplyOutcome> outcomes = new ArrayList<>(items.size());
        Set<Long> voidIds = new LinkedHashSet<>();
        Map<Long, Long> marketIdBySettlementId = new LinkedHashMap<>();
        for (Item item : items) {
            if (!lockedMarketIds.contains(item.marketId())) {
                outcomes.add(PointResultApplyOutcome.DETAIL_NOT_FOUND);
                continue;
            }
            AppliedPointResult applied = switch (item.requestEventType()) {
                case MARKET_REFUND_REQUESTED -> refundTransactionService.applyRefundResultItem(
                        item.idempotencyKey(), item.credited(), item.errorCode());
                case MARKET_SETTLEMENT_PAYOUT_REQUESTED -> settlementTransactionService.applySettlementResultItem(
                        item.idempotencyKey(), item.credited(), item.errorCode());
                default -> throw new IllegalArgumentException("Unsupported requestEventType=" + item.requestEventType());
            };
            if (applied.parentId() != null) {
                if (MARKET_REFUND_REQUESTED.equals(item.requestEventType())) {
                    voidIds.add(applied.parentId());
                } else {
                    marketIdBySettlementId.put(applied.parentId(), item.marketId());
                }
            }
            outcomes.add(applied.outcome());
        }

        voidIds.forEach(refundTransactionService::completeRefundIfDone);
        marketIdBySettlementId.forEach((settlementId, marketId) ->
                settlementTransactionService.completeSettlementIfDone(marketId, settlementId));
        return outcomes;
    }
}
