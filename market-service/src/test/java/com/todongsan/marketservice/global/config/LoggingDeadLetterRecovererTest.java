package com.todongsan.marketservice.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.todongsan.marketservice.market.event.NonRetryableEventException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.ListenerExecutionFailedException;

@ExtendWith(OutputCaptureExtension.class)
class LoggingDeadLetterRecovererTest {

    private final ConsumerRecord<String, String> record =
            new ConsumerRecord<>("memberpoint.point-results", 1, 17L, "42", "{}");
    private final Exception failure = new ListenerExecutionFailedException(
            "listener failed", new NonRetryableEventException("Unknown eventType=POINT_DEBITED"));

    @Test
    void logsAfterPublishingToDlt(CapturedOutput output) {
        ConsumerRecordRecoverer delegate = mock(ConsumerRecordRecoverer.class);

        new LoggingDeadLetterRecoverer(delegate).accept(record, failure);

        verify(delegate).accept(record, failure);
        assertThat(output).contains("Sent to DLT. topic=memberpoint.point-results, partition=1, offset=17, key=42")
                .contains("cause=com.todongsan.marketservice.market.event.NonRetryableEventException: Unknown eventType=POINT_DEBITED")
                .doesNotContain("cause={}");
    }

    @Test
    void logsAndRethrowsWhenDltPublishFails(CapturedOutput output) {
        ConsumerRecordRecoverer delegate = mock(ConsumerRecordRecoverer.class);
        doThrow(new KafkaException("broker down")).when(delegate).accept(record, failure);

        assertThatThrownBy(() -> new LoggingDeadLetterRecoverer(delegate).accept(record, failure))
                .isInstanceOf(KafkaException.class);

        assertThat(output).contains("Failed to publish to DLT. topic=memberpoint.point-results")
                .doesNotContain("Sent to DLT.");
    }
}
