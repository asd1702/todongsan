package com.todongsan.marketservice.market.scheduler;

import com.todongsan.marketservice.market.repository.MarketMapper;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 보존 기간이 지난 market_outbox_event를 배치 단위로 삭제한다 (ASYNC_POINT_FLOW.md §6.1 R7).
 * Debezium은 INSERT만 발행하므로 삭제는 발행에 영향이 없다 (§15.1 사전 검증).
 * 긴 락을 피하기 위해 배치마다 별도 문장으로 삭제하고, 한 실행의 배치 수를 제한한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "market.scheduler.outbox-cleanup",
        name = "enabled",
        havingValue = "true"
)
public class MarketOutboxCleanupScheduler {

    private final MarketMapper marketMapper;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Value("${market.scheduler.outbox-cleanup.retention-days:7}")
    private int retentionDays;

    @Value("${market.scheduler.outbox-cleanup.batch-size:1000}")
    private int batchSize;

    @Value("${market.scheduler.outbox-cleanup.max-batches:10}")
    private int maxBatches;

    @Scheduled(fixedDelayString = "${market.scheduler.outbox-cleanup.fixed-delay-ms:3600000}")
    public void run() {
        if (!running.compareAndSet(false, true)) {
            log.info("Outbox cleanup scheduler is already running. skip.");
            return;
        }
        try {
            int deleted = purge(LocalDateTime.now().minusDays(retentionDays));
            if (deleted > 0) {
                log.info("Outbox cleanup completed. deleted={}, retentionDays={}", deleted, retentionDays);
            }
        } catch (Exception e) {
            log.error("Outbox cleanup scheduler failed.", e);
        } finally {
            running.set(false);
        }
    }

    int purge(LocalDateTime threshold) {
        int total = 0;
        for (int batch = 0; batch < maxBatches; batch++) {
            int deleted = marketMapper.deleteMarketOutboxEventsBefore(threshold, batchSize);
            total += deleted;
            if (deleted < batchSize) {
                break;
            }
        }
        return total;
    }
}
