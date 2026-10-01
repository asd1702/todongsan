package com.todongsan.memberpointservice.global.config;

import com.todongsan.memberpointservice.point.event.DatabaseUnavailableException;
import com.todongsan.memberpointservice.point.event.PointRequestRecordFailedException;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 실제 DefaultErrorHandler로 DB 장애 대기와 일반 실패의 재시도 정책을 확인한다 (ASYNC_POINT_FLOW.md §16.1 Q3, 배치 listener). */
class KafkaConsumerConfigTest {

    private static final int DEFAULT_ATTEMPTS = 5; // 최초 1번 + 재시도 4번
    private static final String TOPIC = "market.settlement-payout-requests";

    private final ConsumerRecord<String, String> record = new ConsumerRecord<>(TOPIC, 0, 17L, "1001", "{}");
    private final ConsumerRecords<String, String> data =
            new ConsumerRecords<>(Map.of(new TopicPartition(TOPIC, 0), List.of(record)));

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<Object, Object> kafkaTemplate = mock(KafkaTemplate.class);
    @SuppressWarnings("unchecked")
    private final Consumer<String, String> consumer = mock(Consumer.class);
    private final MessageListenerContainer container = mock(MessageListenerContainer.class);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(container.getContainerProperties()).thenReturn(new ContainerProperties(TOPIC));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void DB_장애는_기본_재시도_횟수를_넘어도_DLT로_보내지_않는다() {
        DefaultErrorHandler handler = handler(1L, 60_000L);

        for (int i = 0; i < DEFAULT_ATTEMPTS * 3; i++) {
            fail(handler, databaseUnavailable());
        }

        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void DB_복구_후_문제_데이터는_장애_대기가_아니라_기본_재시도_후_DLT로_보낸다() {
        DefaultErrorHandler handler = handler(1L, 60_000L);
        for (int i = 0; i < DEFAULT_ATTEMPTS * 3; i++) {
            fail(handler, databaseUnavailable());
        }

        for (int i = 0; i < DEFAULT_ATTEMPTS - 1; i++) {
            fail(handler, recordFailed());
        }
        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));

        fail(handler, recordFailed());
        verify(kafkaTemplate, times(1)).send(any(ProducerRecord.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void DB_장애도_최대_대기_시간이_지나면_DLT로_보낸다() {
        DefaultErrorHandler handler = handler(1L, 0L);

        fail(handler, databaseUnavailable());

        verify(kafkaTemplate, times(1)).send(any(ProducerRecord.class));
    }

    private DefaultErrorHandler handler(long retryIntervalMs, long maxWaitMs) {
        return new KafkaConsumerConfig().kafkaErrorHandler(kafkaTemplate, retryIntervalMs, maxWaitMs);
    }

    /** 아직 재시도 중이면 handler가 RecordInRetryException(비공개 타입)을 던져 컨테이너가 같은 offset부터 다시 받게 한다. */
    private void fail(DefaultErrorHandler handler, Exception batchFailure) {
        try {
            handler.handleBatchAndReturnRemaining(new ListenerExecutionFailedException("listener failed", batchFailure),
                    data, consumer, container, () -> { });
        } catch (RuntimeException e) {
            if (!"RecordInRetryException".equals(e.getClass().getSimpleName())) {
                throw e;
            }
        }
    }

    private static DatabaseUnavailableException databaseUnavailable() {
        return new DatabaseUnavailableException("Member-Point DB is unavailable", new IllegalStateException("connection refused"), 0);
    }

    private static PointRequestRecordFailedException recordFailed() {
        return new PointRequestRecordFailedException("poison", new RuntimeException("SHA-256 not available"), 0);
    }
}
