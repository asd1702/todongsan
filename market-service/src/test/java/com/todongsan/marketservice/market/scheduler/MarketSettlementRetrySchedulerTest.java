package com.todongsan.marketservice.market.scheduler;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.todongsan.marketservice.global.config.PointTransportProperties;
import com.todongsan.marketservice.market.dto.response.RetrySettlementBatchResponse;
import com.todongsan.marketservice.market.service.MarketSettlementService;
import com.todongsan.marketservice.market.type.PointTransportMode;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MarketSettlementRetrySchedulerTest {

    @Mock
    private MarketSettlementService marketSettlementService;

    @Test
    void runCallsSettlementRetryService() {
        MarketSettlementRetryScheduler scheduler = scheduler(30);
        when(marketSettlementService.retryFailedSettlements(30))
                .thenReturn(new RetrySettlementBatchResponse(30, 0, 0, 0, 0, 0, 0));

        scheduler.run();

        verify(marketSettlementService).retryFailedSettlements(30);
    }

    @Test
    void runSwallowsExceptionAndRestoresRunningFlag() {
        MarketSettlementRetryScheduler scheduler = scheduler(50);
        when(marketSettlementService.retryFailedSettlements(50))
                .thenThrow(new IllegalStateException("boom"))
                .thenReturn(new RetrySettlementBatchResponse(50, 0, 0, 0, 0, 0, 0));

        scheduler.run();
        scheduler.run();

        verify(marketSettlementService, times(2)).retryFailedSettlements(50);
    }

    @Test
    void runSkipsWhenAlreadyRunning() {
        MarketSettlementRetryScheduler scheduler = scheduler(50);
        ReflectionTestUtils.setField(scheduler, "running", new AtomicBoolean(true));

        scheduler.run();

        verify(marketSettlementService, never()).retryFailedSettlements(50);
    }

    @Test
    void runSkipsWhenPointTransportIsAsync() {
        MarketSettlementRetryScheduler scheduler = new MarketSettlementRetryScheduler(
                marketSettlementService,
                new PointTransportProperties(PointTransportMode.ASYNC, null)
        );
        ReflectionTestUtils.setField(scheduler, "limit", 50);

        scheduler.run();

        verify(marketSettlementService, never()).retryFailedSettlements(50);
    }

    private MarketSettlementRetryScheduler scheduler(int limit) {
        MarketSettlementRetryScheduler scheduler = new MarketSettlementRetryScheduler(
                marketSettlementService,
                new PointTransportProperties(null, null)
        );
        ReflectionTestUtils.setField(scheduler, "limit", limit);
        return scheduler;
    }
}
