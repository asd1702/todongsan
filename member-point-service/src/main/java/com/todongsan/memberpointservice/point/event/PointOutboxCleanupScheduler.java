package com.todongsan.memberpointservice.point.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 보존 기간이 지난 point_outbox_event를 배치 단위로 삭제한다 (ASYNC_POINT_FLOW.md §6.1 R7).
 * Debezium은 INSERT만 발행하므로 삭제는 발행에 영향이 없다 (§15.1 사전 검증).
 * 긴 락을 피하기 위해 배치마다 별도 문장으로 삭제하고, 한 실행의 배치 수를 제한한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "member-point.outbox-cleanup", name = "enabled", havingValue = "true")
public class PointOutboxCleanupScheduler {

    private static final String DELETE_SQL = "DELETE FROM point_outbox_event WHERE created_at < ? LIMIT ?";
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private final JdbcTemplate jdbcTemplate;

    @Value("${member-point.outbox-cleanup.retention-days:7}")
    private int retentionDays;

    @Value("${member-point.outbox-cleanup.batch-size:1000}")
    private int batchSize;

    @Value("${member-point.outbox-cleanup.max-batches:10}")
    private int maxBatches;

    @Scheduled(fixedDelayString = "${member-point.outbox-cleanup.fixed-delay-ms:3600000}")
    public void run() {
        try {
            int deleted = purge(LocalDateTime.now(ZONE).minusDays(retentionDays));
            if (deleted > 0) {
                log.info("Outbox cleanup completed. deleted={}, retentionDays={}", deleted, retentionDays);
            }
        } catch (Exception e) {
            log.error("Outbox cleanup scheduler failed.", e);
        }
    }

    int purge(LocalDateTime threshold) {
        int total = 0;
        for (int batch = 0; batch < maxBatches; batch++) {
            int deleted = jdbcTemplate.update(DELETE_SQL, Timestamp.valueOf(threshold), batchSize);
            total += deleted;
            if (deleted < batchSize) {
                break;
            }
        }
        return total;
    }
}
