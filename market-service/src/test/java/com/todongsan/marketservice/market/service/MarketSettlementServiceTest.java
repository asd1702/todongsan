package com.todongsan.marketservice.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.todongsan.marketservice.global.config.PointTransportProperties;
import com.todongsan.marketservice.global.exception.CustomException;
import com.todongsan.marketservice.global.exception.errorcode.MarketErrorCode;
import com.todongsan.marketservice.market.client.MemberPointClient;
import com.todongsan.marketservice.market.dto.response.RetrySettlementBatchResponse;
import com.todongsan.marketservice.market.dto.response.SettleMarketResponse;
import com.todongsan.marketservice.market.type.MarketStatus;
import com.todongsan.marketservice.market.type.SettlementStatus;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MarketSettlementServiceTest {

    private static final PointTransportProperties SYNC = new PointTransportProperties(null, null);

    @Mock
    private MarketSettlementTransactionService transactionService;

    @Mock
    private MemberPointClient memberPointClient;

    @Test
    void retryFailedSettlementsRetriesEachMarketAndAggregatesCounts() {
        MarketSettlementService service = spy(new MarketSettlementService(transactionService, memberPointClient, SYNC));
        when(transactionService.selectMarketIdsForSettlementRetry(3)).thenReturn(List.of(1L, 2L, 3L));
        doReturn(response(1L, MarketStatus.SETTLED)).when(service).retryMarketSettlement(1L);
        doReturn(response(2L, MarketStatus.SETTLEMENT_IN_PROGRESS)).when(service).retryMarketSettlement(2L);
        doThrow(new CustomException(MarketErrorCode.MARKET_INVALID_STATUS))
                .when(service)
                .retryMarketSettlement(3L);

        RetrySettlementBatchResponse response = service.retryFailedSettlements(3);

        assertThat(response.requestedLimit()).isEqualTo(3);
        assertThat(response.scannedMarketCount()).isEqualTo(3);
        assertThat(response.retriedMarketCount()).isEqualTo(3);
        assertThat(response.settledMarketCount()).isEqualTo(1);
        assertThat(response.stillInProgressCount()).isEqualTo(1);
        assertThat(response.skippedCount()).isEqualTo(1);
        assertThat(response.failedCount()).isZero();
        verify(service).retryMarketSettlement(1L);
        verify(service).retryMarketSettlement(2L);
        verify(service).retryMarketSettlement(3L);
    }

    @Test
    void retryFailedSettlementsContinuesAfterUnexpectedException() {
        MarketSettlementService service = spy(new MarketSettlementService(transactionService, memberPointClient, SYNC));
        when(transactionService.selectMarketIdsForSettlementRetry(2)).thenReturn(List.of(1L, 2L));
        doThrow(new IllegalStateException("boom")).when(service).retryMarketSettlement(1L);
        doReturn(response(2L, MarketStatus.SETTLED)).when(service).retryMarketSettlement(2L);

        RetrySettlementBatchResponse response = service.retryFailedSettlements(2);

        assertThat(response.failedCount()).isEqualTo(1);
        assertThat(response.settledMarketCount()).isEqualTo(1);
        assertThat(response.retriedMarketCount()).isEqualTo(2);
    }

    private SettleMarketResponse response(long marketId, MarketStatus marketStatus) {
        return new SettleMarketResponse(
                marketId,
                10L,
                20L,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                0,
                0,
                0,
                0,
                marketStatus,
                marketStatus == MarketStatus.SETTLED ? SettlementStatus.COMPLETED : SettlementStatus.IN_PROGRESS
        );
    }
}
