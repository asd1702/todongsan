package com.todongsan.marketservice.global.config;

import com.todongsan.marketservice.market.event.DatabaseUnavailableException;
import com.todongsan.marketservice.market.event.NonRetryableEventException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;

/**
 * 재시도(1s → 2s → 4s → 8s) 후 실패하면 {topic}-dlt 토픽으로 보낸다 (예: memberpoint.point-results-dlt).
 * NonRetryableEventException은 재시도 없이 바로 DLT로 보낸다. DLT 발행 결과는 ERROR 로그로 남긴다. (ASYNC_POINT_FLOW.md §6.1 R2)
 * DatabaseUnavailableException(DB 장애)은 일정 간격으로 최대 대기 시간까지 기다리고, 그래도 안 되면 DLT로 보낸다. (§16.1 Q3)
 */
@Configuration
@ConditionalOnProperty(prefix = "market.kafka.point-results", name = "enabled", havingValue = "true")
public class KafkaConsumerConfig {

    private static final int MAX_RETRIES = 4;

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(
            KafkaTemplate<?, ?> kafkaTemplate,
            @Value("${market.kafka.point-results.db-unavailable.retry-interval-ms:30000}") long dbRetryIntervalMs,
            @Value("${market.kafka.point-results.db-unavailable.max-wait-ms:3600000}") long dbMaxWaitMs
    ) {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(MAX_RETRIES);
        backOff.setInitialInterval(1_000L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(10_000L);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                new LoggingDeadLetterRecoverer(new DeadLetterPublishingRecoverer(kafkaTemplate)),
                backOff
        );
        errorHandler.addNotRetryableExceptions(NonRetryableEventException.class);
        BackOff waitForDatabase = untilDeadline(dbRetryIntervalMs, dbMaxWaitMs);
        errorHandler.setBackOffFunction((record, exception) ->
                exception instanceof DatabaseUnavailableException ? waitForDatabase : null);
        return errorHandler;
    }

    /** 시도 횟수가 아니라 실제 경과 시간으로 상한을 둔다. 장애 중 한 번의 시도가 커넥션 대기로 길어질 수 있어서다. */
    static BackOff untilDeadline(long intervalMs, long maxWaitMs) {
        return () -> {
            long deadline = System.currentTimeMillis() + maxWaitMs;
            return () -> System.currentTimeMillis() < deadline ? intervalMs : BackOffExecution.STOP;
        };
    }
}
