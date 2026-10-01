package com.todongsan.marketservice.global.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.ListenerExecutionFailedException;

/**
 * DLT 발행 결과를 ERROR 로그로 남긴다 (ASYNC_POINT_FLOW.md §15.1).
 * 발행 실패 시에는 실패 로그를 남기고 예외를 그대로 던져 error handler가 레코드를 다시 처리하게 한다.
 */
@Slf4j
@RequiredArgsConstructor
public class LoggingDeadLetterRecoverer implements ConsumerRecordRecoverer {

    private final ConsumerRecordRecoverer delegate;

    @Override
    public void accept(ConsumerRecord<?, ?> record, Exception exception) {
        Throwable cause = rootCause(exception);
        try {
            delegate.accept(record, exception);
        } catch (RuntimeException publishFailure) {
            log.error("Failed to publish to DLT. topic={}, partition={}, offset={}, key={}, cause={}",
                    record.topic(), record.partition(), record.offset(), record.key(), String.valueOf(cause), publishFailure);
            throw publishFailure;
        }
        log.error("Sent to DLT. topic={}, partition={}, offset={}, key={}, cause={}",
                record.topic(), record.partition(), record.offset(), record.key(), String.valueOf(cause), cause);
    }

    /** Spring Kafka의 래퍼 예외(ListenerExecutionFailedException, BatchListenerFailedException)를 벗겨 실제 원인을 찾는다. */
    private static Throwable rootCause(Exception exception) {
        Throwable cause = exception;
        while ((cause instanceof ListenerExecutionFailedException || cause instanceof BatchListenerFailedException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }
}
