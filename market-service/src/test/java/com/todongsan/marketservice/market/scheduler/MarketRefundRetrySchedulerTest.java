package com.todongsan.marketservice.market.scheduler;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.todongsan.marketservice.global.config.PointTransportProperties;
import com.todongsan.marketservice.market.dto.response.RetryRefundBatchResponse;
import com.todongsan.marketservice.market.service.MarketRefundService;
import com.todongsan.marketservice.market.type.PointTransportMode;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MarketRefundRetrySchedulerTest {

    @Mock
    private MarketRefundService marketRefundService;

    @Test
    void runCallsRefundRetryService() {
        MarketRefundRetryScheduler scheduler = scheduler(30);
        when(marketRefundService.retryFailedRefunds(30))
                .thenReturn(new RetryRefundBatchResponse(30, 0, 0, 0, 0, 0, 0));

        scheduler.run();

        verify(marketRefundService).retryFailedRefunds(30);
    }

    @Test
    void runSwallowsExceptionAndRestoresRunningFlag() {
        MarketRefundRetryScheduler scheduler = scheduler(50);
        when(marketRefundService.retryFailedRefunds(50))
                .thenThrow(new IllegalStateException("boom"))
                .thenReturn(new RetryRefundBatchResponse(50, 0, 0, 0, 0, 0, 0));

        scheduler.run();
        scheduler.run();

        verify(marketRefundService, times(2)).retryFailedRefunds(50);
    }

    @Test
    void runSkipsWhenAlreadyRunning() {
        MarketRefundRetryScheduler scheduler = scheduler(50);
        ReflectionTestUtils.setField(scheduler, "running", new AtomicBoolean(true));

        scheduler.run();

        verify(marketRefundService, never()).retryFailedRefunds(50);
    }

    @Test
    void runSkipsWhenPointTransportIsAsync() {
        MarketRefundRetryScheduler scheduler = new MarketRefundRetryScheduler(
                marketRefundService,
                new PointTransportProperties(PointTransportMode.ASYNC, null)
        );
        ReflectionTestUtils.setField(scheduler, "limit", 50);

        scheduler.run();

        verify(marketRefundService, never()).retryFailedRefunds(50);
    }

    private MarketRefundRetryScheduler scheduler(int limit) {
        MarketRefundRetryScheduler scheduler = new MarketRefundRetryScheduler(
                marketRefundService,
                new PointTransportProperties(null, null)
        );
        ReflectionTestUtils.setField(scheduler, "limit", limit);
        return scheduler;
    }
}
