package com.todongsan.marketservice.market.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.todongsan.marketservice.market.repository.MarketMapper;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MarketOutboxCleanupSchedulerTest {

    private static final LocalDateTime THRESHOLD = LocalDateTime.of(2026, 9, 24, 0, 0);

    @Mock
    private MarketMapper marketMapper;

    @Test
    void stopsWhenBatchIsNotFull() {
        MarketOutboxCleanupScheduler scheduler = scheduler(1000, 10);
        when(marketMapper.deleteMarketOutboxEventsBefore(THRESHOLD, 1000)).thenReturn(1000, 1000, 250);

        assertThat(scheduler.purge(THRESHOLD)).isEqualTo(2250);

        verify(marketMapper, times(3)).deleteMarketOutboxEventsBefore(THRESHOLD, 1000);
    }

    @Test
    void stopsAtMaxBatchesToAvoidLongRuns() {
        MarketOutboxCleanupScheduler scheduler = scheduler(100, 3);
        when(marketMapper.deleteMarketOutboxEventsBefore(THRESHOLD, 100)).thenReturn(100);

        assertThat(scheduler.purge(THRESHOLD)).isEqualTo(300);

        verify(marketMapper, times(3)).deleteMarketOutboxEventsBefore(THRESHOLD, 100);
    }

    @Test
    void runDeletesRowsOlderThanRetentionDays() {
        MarketOutboxCleanupScheduler scheduler = scheduler(1000, 10);
        ReflectionTestUtils.setField(scheduler, "retentionDays", 7);
        when(marketMapper.deleteMarketOutboxEventsBefore(any(LocalDateTime.class), eq(1000))).thenReturn(0);

        scheduler.run();

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(marketMapper).deleteMarketOutboxEventsBefore(captor.capture(), eq(1000));
        assertThat(captor.getValue()).isBetween(
                LocalDateTime.now().minusDays(7).minusMinutes(1),
                LocalDateTime.now().minusDays(7).plusMinutes(1)
        );
    }

    @Test
    void runSkipsWhenAlreadyRunning() {
        MarketOutboxCleanupScheduler scheduler = scheduler(1000, 10);
        ReflectionTestUtils.setField(scheduler, "running", new AtomicBoolean(true));

        scheduler.run();

        verify(marketMapper, never()).deleteMarketOutboxEventsBefore(any(), eq(1000));
    }

    private MarketOutboxCleanupScheduler scheduler(int batchSize, int maxBatches) {
        MarketOutboxCleanupScheduler scheduler = new MarketOutboxCleanupScheduler(marketMapper);
        ReflectionTestUtils.setField(scheduler, "batchSize", batchSize);
        ReflectionTestUtils.setField(scheduler, "maxBatches", maxBatches);
        return scheduler;
    }
}
