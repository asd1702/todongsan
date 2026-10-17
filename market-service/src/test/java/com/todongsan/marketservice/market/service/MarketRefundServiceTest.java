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
import com.todongsan.marketservice.market.dto.response.RefundMarketResponse;
import com.todongsan.marketservice.market.dto.response.RetryRefundBatchResponse;
import com.todongsan.marketservice.market.type.MarketStatus;
import com.todongsan.marketservice.market.type.RefundStatus;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MarketRefundServiceTest {

    private static final PointTransportProperties SYNC = new PointTransportProperties(null, null);

    @Mock
    private MarketRefundTransactionService transactionService;

    @Mock
    private MemberPointClient memberPointClient;

    @Test
    void retryFailedRefundsReturnsEmptyCountsWhenNoTargetMarkets() {
        MarketRefundService service = new MarketRefundService(transactionService, memberPointClient, SYNC);
        when(transactionService.selectMarketIdsForRefundRetry(50)).thenReturn(List.of());

        RetryRefundBatchResponse response = service.retryFailedRefunds(50);

        assertThat(response.requestedLimit()).isEqualTo(50);
        assertThat(response.scannedMarketCount()).isZero();
        assertThat(response.retriedMarketCount()).isZero();
        assertThat(response.completedMarketCount()).isZero();
        assertThat(response.stillInProgressCount()).isZero();
        assertThat(response.skippedCount()).isZero();
        assertThat(response.failedCount()).isZero();
    }

    @Test
    void retryFailedRefundsRetriesEachMarketAndAggregatesCounts() {
        MarketRefundService service = spy(new MarketRefundService(transactionService, memberPointClient, SYNC));
        when(transactionService.selectMarketIdsForRefundRetry(4)).thenReturn(List.of(1L, 2L, 3L, 4L));
        doReturn(response(1L, RefundStatus.COMPLETED)).when(service).retryRefundMarket(1L);
        doReturn(response(2L, RefundStatus.IN_PROGRESS)).when(service).retryRefundMarket(2L);
        doThrow(new CustomException(MarketErrorCode.MARKET_INVALID_STATUS))
                .when(service)
                .retryRefundMarket(3L);
        doThrow(new IllegalStateException("boom")).when(service).retryRefundMarket(4L);

        RetryRefundBatchResponse response = service.retryFailedRefunds(4);

        assertThat(response.requestedLimit()).isEqualTo(4);
        assertThat(response.scannedMarketCount()).isEqualTo(4);
        assertThat(response.retriedMarketCount()).isEqualTo(4);
        assertThat(response.completedMarketCount()).isEqualTo(1);
        assertThat(response.stillInProgressCount()).isEqualTo(1);
        assertThat(response.skippedCount()).isEqualTo(1);
        assertThat(response.failedCount()).isEqualTo(1);
        verify(service).retryRefundMarket(1L);
        verify(service).retryRefundMarket(2L);
        verify(service).retryRefundMarket(3L);
        verify(service).retryRefundMarket(4L);
    }

    @Test
    void retryFailedRefundsContinuesAfterUnexpectedException() {
        MarketRefundService service = spy(new MarketRefundService(transactionService, memberPointClient, SYNC));
        when(transactionService.selectMarketIdsForRefundRetry(2)).thenReturn(List.of(1L, 2L));
        doThrow(new IllegalStateException("boom")).when(service).retryRefundMarket(1L);
        doReturn(response(2L, RefundStatus.COMPLETED)).when(service).retryRefundMarket(2L);

        RetryRefundBatchResponse response = service.retryFailedRefunds(2);

        assertThat(response.failedCount()).isEqualTo(1);
        assertThat(response.completedMarketCount()).isEqualTo(1);
        assertThat(response.retriedMarketCount()).isEqualTo(2);
    }

    private RefundMarketResponse response(long marketId, RefundStatus refundStatus) {
        return new RefundMarketResponse(
                marketId,
                10L,
                0,
                0,
                0,
                0,
                MarketStatus.VOIDED,
                refundStatus
        );
    }
}
