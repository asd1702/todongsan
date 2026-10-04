package com.todongsan.memberpointservice.global.warmup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.todongsan.memberpointservice.global.exception.CustomException;
import com.todongsan.memberpointservice.point.dto.request.SpendRequest;
import com.todongsan.memberpointservice.point.service.PointInternalService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

/**
 * 차감(spend) 경로를 트래픽 전에 데운다 (docs/architecture/SPEND_CIRCUIT_BREAKER.md §6, 카카오페이 JVM 웜업 사례와 같은 방식).
 *
 * ApplicationRunner가 끝나야 Spring Boot가 readiness를 ACCEPTING_TRAFFIC으로 바꾸므로, 프로브를 쓰는 환경에서는
 * 워밍업 중 트래픽이 들어오지 않는다. 데이터를 남기지 않는다:
 * ① 자기 자신에게 금액 0인 차감 HTTP 요청 → 쓰기 전에 POINT_INVALID_AMOUNT로 끝난다 (Tomcat·Security·MVC·Jackson·멱등성 키 조회)
 * ② 롤백 전용 트랜잭션 안에서 실제 차감 서비스 호출 (회원 조회·잔액 UPDATE·이력 INSERT) → 전부 롤백
 * 워밍업 실패는 기동을 막지 않는다 (데우지 못했을 뿐 기능 문제는 아니다).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "member-point.warmup", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SpendPathWarmup implements ApplicationRunner {

    static final String KEY_PREFIX = "WARMUP:";

    private final PointInternalService pointInternalService;
    private final TransactionTemplate rollbackOnly;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final Environment environment;
    private final int httpRequests;
    private final int spendCalls;

    public SpendPathWarmup(
            PointInternalService pointInternalService,
            PlatformTransactionManager transactionManager,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            Environment environment,
            @Value("${member-point.warmup.http-requests:200}") int httpRequests,
            @Value("${member-point.warmup.spend-calls:100}") int spendCalls
    ) {
        this.pointInternalService = pointInternalService;
        this.rollbackOnly = new TransactionTemplate(transactionManager);
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.environment = environment;
        this.httpRequests = httpRequests;
        this.spendCalls = spendCalls;
    }

    @Override
    public void run(ApplicationArguments args) {
        long started = System.nanoTime();
        int http = 0;
        int spend = 0;
        try {
            http = warmHttp();
            spend = warmSpendInRolledBackTransactions();
        } catch (RuntimeException e) {
            log.warn("Spend path warmup stopped early. http={}, spend={}, cause={}", http, spend, e.toString());
        }
        log.info("Spend path warmup done. http={}, spend={}, elapsedMs={}", http, spend, (System.nanoTime() - started) / 1_000_000);
    }

    /** 금액 0 차감은 멱등성 키 조회 뒤 금액 검증에서 거절된다. 아무것도 쓰지 않는다 */
    private int warmHttp() {
        String port = environment.getProperty("local.server.port");
        if (port == null) {
            return 0;
        }
        RestClient client = RestClient.create("http://localhost:" + port);
        Map<String, Object> body = Map.of("memberId", 1, "type", "SPEND_MARKET", "amount", 0,
                "referenceType", "MARKET_PREDICTION", "referenceId", 1, "reason", "warmup");
        for (int i = 0; i < httpRequests; i++) {
            client.post().uri("/internal/api/v1/points/spend")
                    .header("Idempotency-Key", KEY_PREFIX + UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((request, response) -> response.getStatusCode());
        }
        return httpRequests;
    }

    /** 가장 작은 ID의 회원으로 실제 차감을 실행하고 롤백한다. 회원이 없으면 건너뛴다 */
    private int warmSpendInRolledBackTransactions() {
        List<Long> members = jdbcTemplate.queryForList(
                "SELECT id FROM member WHERE deleted_at IS NULL ORDER BY id LIMIT 1", Long.class);
        if (members.isEmpty()) {
            return 0;
        }
        SpendRequest request = objectMapper.convertValue(Map.of("memberId", members.get(0), "type", "SPEND_MARKET",
                "amount", "1.00", "referenceType", "MARKET_PREDICTION", "referenceId", 1, "reason", "warmup"),
                SpendRequest.class);
        for (int i = 0; i < spendCalls; i++) {
            rollbackOnly.executeWithoutResult(status -> {
                status.setRollbackOnly();
                try {
                    pointInternalService.spend(KEY_PREFIX + UUID.randomUUID(), request);
                } catch (CustomException ignored) {
                    // 잔액 부족 등 업무 실패도 같은 경로를 데운다
                }
            });
        }
        return spendCalls;
    }
}
