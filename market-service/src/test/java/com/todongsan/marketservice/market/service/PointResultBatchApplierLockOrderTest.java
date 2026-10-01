package com.todongsan.marketservice.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import com.todongsan.marketservice.market.entity.MarketSettlementDetail;
import com.todongsan.marketservice.market.repository.MarketMapper;
import com.todongsan.marketservice.market.service.PointResultBatchApplier.Item;
import com.todongsan.marketservice.market.type.TransactionItemStatus;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

/** 배치의 market은 받은 순서와 무관하게 ID 오름차순으로 먼저 잠그고, 반영은 받은 순서대로 한다 (§16.1 Q5, §17.6). */
@ExtendWith(MockitoExtension.class)
class PointResultBatchApplierLockOrderTest {

    @Mock
    private MarketMapper marketMapper;

    @Mock
    private MarketRefundTransactionService refundTransactionService;

    @Mock
    private MarketSettlementTransactionService settlementTransactionService;

    @Mock
    private JdbcTemplate jdbcTemplate;

    /** 정산 detail은 market 다음에 한 번에 잠그고, 반영(UPDATE)은 완료 판정보다 먼저 기록한다 (§17.6). */
    @Test
    void locksMarketsInAscendingIdOrderBeforeApplyingInReceivedOrder() {
        PointResultBatchApplier applier = new PointResultBatchApplier(
                marketMapper, refundTransactionService, settlementTransactionService, jdbcTemplate);
        when(marketMapper.lockMarketsByIdsInOrder(List.of(42L, 77L))).thenReturn(List.of(42L, 77L));
        when(marketMapper.lockSettlementDetailsByIdempotencyKeys(List.of("S-77"))).thenReturn(List.of(pendingDetail()));
        when(refundTransactionService.applyRefundResultItem(anyString(), anyBoolean(), any()))
                .thenReturn(new AppliedPointResult(PointResultApplyOutcome.APPLIED, 500L));

        List<PointResultApplyOutcome> outcomes = applier.applyBatch(List.of(
                new Item(PointResultBatchApplier.MARKET_SETTLEMENT_PAYOUT_REQUESTED, 77L, "S-77", true, null),
                new Item(PointResultBatchApplier.MARKET_REFUND_REQUESTED, 42L, "R-42", true, null)
        ));

        assertThat(outcomes).containsOnly(PointResultApplyOutcome.APPLIED);
        InOrder order = inOrder(marketMapper, settlementTransactionService, refundTransactionService, jdbcTemplate);
        order.verify(marketMapper).lockMarketsByIdsInOrder(List.of(42L, 77L));
        order.verify(marketMapper).lockSettlementDetailsByIdempotencyKeys(List.of("S-77"));
        order.verify(refundTransactionService).applyRefundResultItem("R-42", true, null);
        order.verify(jdbcTemplate, times(2)).batchUpdate(anyString(), anyList());
        order.verify(refundTransactionService).completeRefundIfDone(500L);
        order.verify(settlementTransactionService).completeSettlementIfDone(77L, 600L);
    }

    private static MarketSettlementDetail pendingDetail() {
        MarketSettlementDetail detail = new MarketSettlementDetail();
        detail.setId(1L);
        detail.setSettlementId(600L);
        detail.setPredictionId(7L);
        detail.setSettledAmount(new BigDecimal("195.00"));
        detail.setStatus(TransactionItemStatus.PENDING);
        detail.setIdempotencyKey("S-77");
        return detail;
    }
}
