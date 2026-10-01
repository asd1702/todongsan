package com.todongsan.marketservice.market.scheduler;

import com.todongsan.marketservice.global.config.PointTransportProperties;
import com.todongsan.marketservice.market.dto.response.RetrySettlementBatchResponse;
import com.todongsan.marketservice.market.service.MarketSettlementService;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "market.scheduler.settlement-retry",
        name = "enabled",
        havingValue = "true"
)
public class MarketSettlementRetryScheduler {

    private final MarketSettlementService marketSettlementService;
    private final PointTransportProperties pointTransportProperties;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Value("${market.scheduler.settlement-retry.limit:50}")
    private int limit;

    @Scheduled(fixedDelayString = "${market.scheduler.settlement-retry.fixed-delay-ms:180000}")
    public void run() {
        if (pointTransportProperties.isAsync()) {
            // async 모드에서는 PENDING detail이 Kafka로 처리 중이므로 Feign 재전송을 하지 않는다.
            log.debug("Point transport is async. skip settlement retry scheduler.");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.info("Settlement retry scheduler is already running. skip.");
            return;
        }
        try {
            RetrySettlementBatchResponse response = marketSettlementService.retryFailedSettlements(limit);
            log.info("Settlement retry scheduler completed. response={}", response);
        } catch (Exception e) {
            log.error("Settlement retry scheduler failed.", e);
        } finally {
            running.set(false);
        }
    }
}
