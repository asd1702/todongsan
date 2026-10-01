package com.todongsan.marketservice.global.config;

import com.todongsan.marketservice.market.event.NonRetryableEventException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/**
 * 재시도(1s → 2s → 4s → 8s) 후 실패하면 {topic}-dlt 토픽으로 보낸다 (예: memberpoint.point-results-dlt).
 * NonRetryableEventException은 재시도 없이 바로 DLT로 보낸다. (ASYNC_POINT_FLOW.md §6.1 R2)
 */
@Configuration
@ConditionalOnProperty(prefix = "market.kafka.point-results", name = "enabled", havingValue = "true")
public class KafkaConsumerConfig {

    private static final int MAX_RETRIES = 4;

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<?, ?> kafkaTemplate) {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(MAX_RETRIES);
        backOff.setInitialInterval(1_000L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(10_000L);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(new DeadLetterPublishingRecoverer(kafkaTemplate), backOff);
        errorHandler.addNotRetryableExceptions(NonRetryableEventException.class);
        return errorHandler;
    }
}
