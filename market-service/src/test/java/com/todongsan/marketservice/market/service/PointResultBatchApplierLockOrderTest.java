package com.todongsan.marketservice.market.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

import com.todongsan.marketservice.market.repository.MarketMapper;
import com.todongsan.marketservice.market.service.PointResultBatchApplier.Item;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 배치의 market은 받은 순서와 무관하게 ID 오름차순으로 먼저 잠그고, 반영은 받은 순서대로 한다 (§16.1 Q5). */
@ExtendWith(MockitoExtension.class)
class PointResultBatchApplierLockOrderTest {

    @Mock
    private MarketMapper marketMapper;

    @Mock
    private MarketRefundTransactionService refundTransactionService;

    @Mock
    private MarketSettlementTransactionService settlementTransactionService;

    @Test
    void locksMarketsInAscendingIdOrderBeforeApplyingInReceivedOrder() {
        PointResultBatchApplier applier = new PointResultBatchApplier(
                marketMapper, refundTransactionService, settlementTransactionService);
        when(marketMapper.lockMarketsByIdsInOrder(List.of(42L, 77L))).thenReturn(List.of(42L, 77L));
        when(refundTransactionService.applyRefundResultItem(anyString(), anyBoolean(), any()))
                .thenReturn(new AppliedPointResult(PointResultApplyOutcome.APPLIED, 500L));
        when(settlementTransactionService.applySettlementResultItem(anyString(), anyBoolean(), any()))
                .thenReturn(new AppliedPointResult(PointResultApplyOutcome.APPLIED, 600L));

        applier.applyBatch(List.of(
                new Item(PointResultBatchApplier.MARKET_SETTLEMENT_PAYOUT_REQUESTED, 77L, "S-77", true, null),
                new Item(PointResultBatchApplier.MARKET_REFUND_REQUESTED, 42L, "R-42", true, null)
        ));

        InOrder order = inOrder(marketMapper, settlementTransactionService, refundTransactionService);
        order.verify(marketMapper).lockMarketsByIdsInOrder(List.of(42L, 77L));
        order.verify(settlementTransactionService).applySettlementResultItem("S-77", true, null);
        order.verify(refundTransactionService).applyRefundResultItem("R-42", true, null);
        order.verify(refundTransactionService).completeRefundIfDone(500L);
        order.verify(settlementTransactionService).completeSettlementIfDone(77L, 600L);
    }
}
