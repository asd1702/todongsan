package com.todongsan.memberpointservice.global.config;

import com.todongsan.memberpointservice.point.event.NonRetryableEventException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/**
 * 재시도(1s → 2s → 4s → 8s) 후 실패하면 {topic}-dlt 토픽으로 보낸다 (예: market.point-requests-dlt).
 * NonRetryableEventException은 재시도 없이 바로 DLT로 보낸다.
 */
@Configuration
@ConditionalOnProperty(prefix = "member-point.kafka.market-point-requests", name = "enabled", havingValue = "true")
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
