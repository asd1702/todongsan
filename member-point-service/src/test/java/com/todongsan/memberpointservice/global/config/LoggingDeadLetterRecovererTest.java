package com.todongsan.memberpointservice.global.config;

import com.todongsan.memberpointservice.point.event.NonRetryableEventException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.ListenerExecutionFailedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@ExtendWith(OutputCaptureExtension.class)
class LoggingDeadLetterRecovererTest {

    private final ConsumerRecord<String, String> record =
            new ConsumerRecord<>("market.point-requests", 2, 17L, "101", "{not-json");
    private final Exception failure = new ListenerExecutionFailedException(
            "listener failed", new NonRetryableEventException("Malformed market point request event"));

    @Test
    void DLT_발행_후_ERROR_로그를_남긴다(CapturedOutput output) {
        ConsumerRecordRecoverer delegate = mock(ConsumerRecordRecoverer.class);

        new LoggingDeadLetterRecoverer(delegate).accept(record, failure);

        verify(delegate).accept(record, failure);
        assertThat(output).contains("Sent to DLT. topic=market.point-requests, partition=2, offset=17, key=101")
                .contains("cause=com.todongsan.memberpointservice.point.event.NonRetryableEventException: Malformed market point request event")
                .doesNotContain("cause={}");
    }

    @Test
    void DLT_발행이_실패하면_실패_로그를_남기고_예외를_다시_던진다(CapturedOutput output) {
        ConsumerRecordRecoverer delegate = mock(ConsumerRecordRecoverer.class);
        doThrow(new KafkaException("broker down")).when(delegate).accept(record, failure);

        assertThatThrownBy(() -> new LoggingDeadLetterRecoverer(delegate).accept(record, failure))
                .isInstanceOf(KafkaException.class);

        assertThat(output).contains("Failed to publish to DLT. topic=market.point-requests")
                .doesNotContain("Sent to DLT.");
    }
}
