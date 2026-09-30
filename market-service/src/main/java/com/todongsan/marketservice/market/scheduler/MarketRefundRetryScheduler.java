package com.todongsan.marketservice.market.scheduler;

import com.todongsan.marketservice.global.config.PointTransportProperties;
import com.todongsan.marketservice.market.dto.response.RetryRefundBatchResponse;
import com.todongsan.marketservice.market.service.MarketRefundService;
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
        prefix = "market.scheduler.refund-retry",
        name = "enabled",
        havingValue = "true"
)
public class MarketRefundRetryScheduler {

    private final MarketRefundService marketRefundService;
    private final PointTransportProperties pointTransportProperties;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Value("${market.scheduler.refund-retry.limit:50}")
    private int limit;

    @Scheduled(fixedDelayString = "${market.scheduler.refund-retry.fixed-delay-ms:180000}")
    public void run() {
        if (pointTransportProperties.isAsync()) {
            // async 모드에서는 PENDING detail이 Kafka로 처리 중이므로 Feign 재전송을 하지 않는다.
            log.debug("Point transport is async. skip refund retry scheduler.");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.info("Refund retry scheduler is already running. skip.");
            return;
        }
        try {
            RetryRefundBatchResponse response = marketRefundService.retryFailedRefunds(limit);
            log.info("Refund retry scheduler completed. response={}", response);
        } catch (Exception e) {
            log.error("Refund retry scheduler failed.", e);
        } finally {
            running.set(false);
        }
    }
}
