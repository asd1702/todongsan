package com.todongsan.memberpointservice.point.event;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PointOutboxCleanupSchedulerTest {

    private static final LocalDateTime THRESHOLD = LocalDateTime.of(2026, 9, 24, 0, 0);

    @Mock
    JdbcTemplate jdbcTemplate;

    @Test
    void 배치가_가득_차지_않으면_멈춘다() {
        PointOutboxCleanupScheduler scheduler = scheduler(1000, 10);
        when(jdbcTemplate.update(anyString(), eq(Timestamp.valueOf(THRESHOLD)), eq(1000))).thenReturn(1000, 400);

        assertThat(scheduler.purge(THRESHOLD)).isEqualTo(1400);

        verify(jdbcTemplate, times(2)).update(anyString(), eq(Timestamp.valueOf(THRESHOLD)), eq(1000));
    }

    @Test
    void 한_번_실행에_최대_배치_수까지만_삭제한다() {
        PointOutboxCleanupScheduler scheduler = scheduler(100, 3);
        when(jdbcTemplate.update(anyString(), eq(Timestamp.valueOf(THRESHOLD)), eq(100))).thenReturn(100);

        assertThat(scheduler.purge(THRESHOLD)).isEqualTo(300);

        verify(jdbcTemplate, times(3)).update(anyString(), eq(Timestamp.valueOf(THRESHOLD)), eq(100));
    }

    private PointOutboxCleanupScheduler scheduler(int batchSize, int maxBatches) {
        PointOutboxCleanupScheduler scheduler = new PointOutboxCleanupScheduler(jdbcTemplate);
        ReflectionTestUtils.setField(scheduler, "batchSize", batchSize);
        ReflectionTestUtils.setField(scheduler, "maxBatches", maxBatches);
        return scheduler;
    }
}
