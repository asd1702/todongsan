package com.todongsan.marketservice.market.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import com.todongsan.marketservice.market.client.MemberPointClient;
import com.todongsan.marketservice.market.client.MemberPointRefundBatchRequest;
import com.todongsan.marketservice.market.client.MemberPointRefundBatchResponse;
import com.todongsan.marketservice.market.client.MemberPointSettlementBatchRequest;
import com.todongsan.marketservice.market.client.MemberPointSettlementBatchResponse;
import com.todongsan.marketservice.market.client.MemberPointTransactionStatusResponse;
import com.todongsan.marketservice.market.client.PointSpendCommand;
import com.todongsan.marketservice.market.debug.MarketPredictionDeadlockDebug;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * [벤치 v3] 예측 참여 경로 부하 실험.
 *
 * <h2>v2에서 고친 것</h2>
 * <ol>
 *   <li><b>확정 측정 모드 분리</b> ({@code -Dbench.confirmMode=sync|async}).
 *       v2는 Observer를 끄면 M2 표본과 Confirmation TPS가 그대로 0이 되는 구조였다.
 *       지표 의미까지 같이 분기해야 한다.
 *       <ul>
 *         <li><b>sync</b> (현재 AS-IS) — Observer 없음. 확정이 응답보다 먼저 커밋되므로
 *             {@code M2 = 요청→응답}을 <b>상한(upper bound)</b>으로 쓴다.
 *             Confirmation TPS = 2xx 성공 수 / (마지막 응답 − 시작).</li>
 *         <li><b>async</b> (향후 EDA) — Observer로 CONFIRMED 관측.
 *             Confirmation TPS = 관측된 확정 수 / (마지막 확정 − 시작).</li>
 *       </ul></li>
 *   <li><b>Observer 중점 보정</b> — 확정 시각을 "발견한 스캔 시각"이 아니라
 *       {@code (직전 스캔 + 이번 스캔) / 2}로 찍는다. 폴링 주기 P의 계통 편향 +P/2를 없앤다.
 *       다만 이는 <b>편향을 줄이는 추정</b>일 뿐이고 ±P/2의 관측 오차는 남는다.
 *       async 결과에는 반드시 "폴링 관측, 주기 P" 를 함께 적을 것.</li>
 *   <li><b>자원 계측 추가</b> — 라운드 내내 샘플링한다.
 *       Hikari active / awaiting, Tomcat busy threads, MySQL Threads_running,
 *       그리고 라운드 전후 {@code Innodb_row_lock_waits / Innodb_row_lock_time} 델타.
 *       <b>처리량 정체의 원인을 "락"이라고 말하려면 이 숫자가 있어야 한다.</b></li>
 *   <li><b>max_connections 사전 검증</b> — Hikari pool보다 작으면 즉시 실패시킨다.
 *       (C=200 / pool 큰 조건에서 "Too many connections"로 뒤늦게 죽는 것 방지)</li>
 *   <li><b>파생 지표 이름 정정</b> — {@code 1 / Confirmation TPS}는 "락 점유 시간"이 아니다.
 *       락·커밋·CPU·MyBatis·커넥션 등 모든 직렬 구간의 합이다.
 *       <b>직렬 병목 서비스 수요(serial service demand)</b>로만 부른다.</li>
 * </ol>
 *
 * <h2>실행 예</h2>
 * <pre>
 * # S. 단건 정상상태 baseline (3회 반복해서 안정성부터 확인)
 * -Dbench.enabled=true -Dbench.confirmMode=sync -Dbench.concurrency=1 \
 * -Dbench.warmup=2000 -Dbench.requests=2000 -Dbench.rounds=3 -Dbench.pool=10
 *
 * # A. 락 격리 sweep (pool을 C보다 크게)
 * -Dbench.enabled=true -Dbench.confirmMode=sync -Dbench.concurrency=200 \
 * -Dbench.requests=1000 -Dbench.rounds=1 -Dbench.pool=220
 *
 * # B. 시스템 현실 (운영 pool = Spring Boot 기본 10)
 * -Dbench.enabled=true -Dbench.confirmMode=sync -Dbench.concurrency=200 \
 * -Dbench.requests=1000 -Dbench.rounds=1 -Dbench.pool=10
 * </pre>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // 기본 10 = 이 서비스의 실제 설정(yml에 지정 없음 → Spring Boot 기본값).
                "spring.datasource.hikari.maximum-pool-size=${bench.pool:10}",
                "spring.datasource.hikari.connection-timeout=${bench.poolTimeoutMs:30000}",
                "server.tomcat.threads.max=${bench.tomcatThreads:200}",
                "todongsan.benchmark.deadlock-debug.enabled=${bench.deadlockDebug:false}"
        }
)
@ActiveProfiles("mysqlit")
@Import(MarketPredictionLatencyBenchmarkTest.BenchClientConfig.class)
@EnabledIfSystemProperty(named = "bench.enabled", matches = "true")
class MarketPredictionLatencyBenchmarkTest {

    private static final BigDecimal BET = new BigDecimal("100.00");
    private static final int OPTION_COUNT = 2;

    private static final String MODE = System.getProperty("bench.mode", "closed");
    /** sync = AS-IS(응답이 곧 확정) / async = EDA(202 후 백그라운드 확정) */
    private static final String CONFIRM_MODE = System.getProperty("bench.confirmMode", "sync");
    private static final int CONCURRENCY = intProp("bench.concurrency", 50);
    private static final int REQUESTS = intProp("bench.requests", 2000);
    private static final int RATE = intProp("bench.rate", 200);
    private static final int DURATION_SEC = intProp("bench.durationSec", 60);
    private static final int WARMUP = intProp("bench.warmup", 2000);
    private static final int ROUNDS = intProp("bench.rounds", 3);
    private static final int MARKETS = intProp("bench.markets", 1);
    private static final long CONFIRM_POLL_MS = intProp("bench.confirmPollMs", 50);
    private static final long PROBE_MS = intProp("bench.probeMs", 200);
    private static final int MAX_INFLIGHT = intProp("bench.maxInflight", 3000);
    private static final boolean DEADLOCK_DEBUG = Boolean.getBoolean("bench.deadlockDebug");

    private static boolean asyncConfirm() {
        return "async".equalsIgnoreCase(CONFIRM_MODE);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private MarketPredictionDeadlockDebug deadlockDebug;

    @BeforeEach
    void guardEnvironment() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class))
                .as("운영/로컬 DB 오염 방지 — market_test 전용")
                .isEqualTo("market_test");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = TRUE", Integer.class))
                .isPositive();

        // 커넥션 상한 사전 검증. 뒤늦게 "Too many connections"로 죽는 대신 여기서 원인을 말하고 멈춘다.
        int maxConnections = Integer.parseInt(showVariable("max_connections"));
        int poolSize = dataSource instanceof HikariDataSource hikari ? hikari.getMaximumPoolSize() : 0;
        assertThat(maxConnections)
                .as("""
                        MySQL max_connections(%d)가 Hikari pool(%d)보다 작거나 여유가 없다.
                        infra/docker-compose.test.yml 의 command 에 --max-connections=%d 이상을 추가하고
                        컨테이너를 재생성할 것 (tmpfs 라 데이터 손실 없음).""",
                        maxConnections, poolSize, poolSize + 20)
                .isGreaterThanOrEqualTo(poolSize + 20);
    }

    @Test
    @DisplayName("[벤치] 예측 참여 경로 — M1/M2 지연, Admission/Confirmation TPS, 자원 계측, 정합성")
    void benchmarkPredictionPath() throws Exception {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        printEnvironmentHeader();

        List<RoundResult> results = new ArrayList<>();
        for (int round = 1; round <= ROUNDS; round++) {
            resetMarketData();
            if (WARMUP > 0) {
                runClosedLoop(http, WARMUP, 1_000_000_000L, false);
                resetMarketData();
            }
            if (DEADLOCK_DEBUG) {
                deadlockDebug.reset();
            }
            RoundResult result = "open".equalsIgnoreCase(MODE)
                    ? runOpenLoop(http)
                    : runClosedLoop(http, REQUESTS, 0L, true);
            verifyInvariants(result);
            results.add(result);
            printRound(round, result);
        }
        printSummary(results);
    }

    // ----------------------------------------------------------- closed-loop

    private RoundResult runClosedLoop(HttpClient http, int total, long memberIdOffset, boolean record)
            throws Exception {
        ConcurrentLinkedQueue<Long> queue = new ConcurrentLinkedQueue<>();
        for (int i = 1; i <= total; i++) {
            queue.add(memberIdOffset + i);
        }

        Recorder recorder = new Recorder(record);
        ConfirmObserver observer = (record && asyncConfirm())
                ? ConfirmObserver.start(jdbc, CONFIRM_POLL_MS) : null;
        ResourceProbe probe = record ? startProbe() : null;
        Map<String, Long> lockBefore = record ? innodbLockStatus() : Map.of();

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch ready = new CountDownLatch(CONCURRENCY);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CONCURRENCY);

        for (int t = 0; t < CONCURRENCY; t++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    Long memberId;
                    while ((memberId = queue.poll()) != null) {
                        long now = System.nanoTime();
                        fire(http, memberId, now, now, recorder); // closed-loop: 의도 시각 == 송신 시각
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        long wallStart = System.nanoTime();
        start.countDown();
        boolean finished = done.await(20, TimeUnit.MINUTES);
        pool.shutdownNow();

        return finish(recorder, observer, probe, lockBefore, wallStart, finished, 0, record);
    }

    // ------------------------------------------------------------- open-loop

    private RoundResult runOpenLoop(HttpClient http) throws Exception {
        int total = RATE * DURATION_SEC;
        Recorder recorder = new Recorder(true);
        ConfirmObserver observer = asyncConfirm() ? ConfirmObserver.start(jdbc, CONFIRM_POLL_MS) : null;
        ResourceProbe probe = startProbe();
        Map<String, Long> lockBefore = innodbLockStatus();

        ThreadPoolExecutor senders = new ThreadPoolExecutor(
                64, MAX_INFLIGHT, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(1));
        AtomicInteger generatorSaturated = new AtomicInteger();
        AtomicLong memberSeq = new AtomicLong();

        long intervalNanos = 1_000_000_000L / RATE;
        long wallStart = System.nanoTime();

        for (int i = 0; i < total; i++) {
            long intended = wallStart + i * intervalNanos;
            long sleepNanos = intended - System.nanoTime();
            if (sleepNanos > 0) {
                TimeUnit.NANOSECONDS.sleep(sleepNanos);
            }
            long memberId = memberSeq.incrementAndGet();
            try {
                senders.execute(() -> fire(http, memberId, intended, System.nanoTime(), recorder));
            } catch (RejectedExecutionException e) {
                generatorSaturated.incrementAndGet();
            }
        }

        senders.shutdown();
        boolean finished = senders.awaitTermination(20, TimeUnit.MINUTES);
        return finish(recorder, observer, probe, lockBefore, wallStart, finished,
                generatorSaturated.get(), true);
    }

    // ------------------------------------------------------------------ core

    private void fire(HttpClient http, long memberId, long intendedNanos, long sendNanos, Recorder recorder) {
        long marketId = (memberId % MARKETS) + 1;
        String requestId = "bench-%d-%d".formatted(marketId, memberId);
        boolean ok;
        String failReason = null;
        String failureCategory = null;
        try {
            HttpResponse<String> response = http.send(
                    predictionRequest(marketId, memberId, requestId), HttpResponse.BodyHandlers.ofString());
            ok = response.statusCode() / 100 == 2;
            if (!ok) {
                failReason = response.statusCode() + " " + truncate(response.body());
                failureCategory = classifyHttpFailure(response.statusCode(), requestId);
            }
        } catch (IOException | InterruptedException e) {
            ok = false;
            failReason = e.getClass().getSimpleName() + ": " + e.getMessage();
            failureCategory = e instanceof HttpTimeoutException ? "TIMEOUT" : "IO_ERROR";
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        recorder.record(marketId, memberId, intendedNanos, sendNanos, System.nanoTime(), ok, failReason,
                failureCategory);
    }

    private String classifyHttpFailure(int statusCode, String requestId) {
        if (statusCode / 100 == 4) {
            return "HTTP_4XX";
        }
        if (statusCode == 500 && DEADLOCK_DEBUG) {
            String appCategory = deadlockDebug.takeFailureCategory(requestId);
            if (appCategory != null) {
                return appCategory;
            }
        }
        return statusCode >= 500 ? "HTTP_500_OTHER" : "OTHER";
    }

    private RoundResult finish(Recorder recorder, ConfirmObserver observer, ResourceProbe probe,
                               Map<String, Long> lockBefore, long wallStart, boolean finished,
                               int generatorSaturated, boolean record) throws Exception {
        if (observer != null) {
            observer.drain(recorder.successCount(), System.nanoTime() + Duration.ofMinutes(3).toNanos());
        }
        Map<String, Long> confirmedAt = observer == null ? Map.of() : observer.stopAndCollect();
        ResourceSnapshot resources = probe == null ? ResourceSnapshot.empty() : probe.stop();
        Map<String, Long> lockDelta = record ? delta(lockBefore, innodbLockStatus()) : Map.of();

        return new RoundResult(finished, recorder, confirmedAt, resources, lockDelta,
                wallStart, recorder.lastResponseNanos(), generatorSaturated);
    }

    private HttpRequest predictionRequest(long marketId, long memberId, String requestId) {
        long optionId = optionId(marketId, memberId % 2 == 0);
        String body = """
                {"marketOptionId":%d,"pointAmount":%s}
                """.formatted(optionId, BET.toPlainString());
        return HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/markets/" + marketId + "/predictions"))
                .timeout(Duration.ofMinutes(3))
                .header("Content-Type", "application/json")
                .header("X-Member-Id", String.valueOf(memberId))
                .header(MarketPredictionDeadlockDebug.REQUEST_ID_HEADER, requestId)
                .header("Idempotency-Key",
                        "MARKET_PREDICTION_SPEND:market:%d:member:%d".formatted(marketId, memberId))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private static long optionId(long marketId, boolean second) {
        return (marketId - 1) * OPTION_COUNT + (second ? 2 : 1);
    }

    // ------------------------------------------------------- resource probing

    private HikariPoolMXBean hikariPool() {
        return dataSource instanceof HikariDataSource hikari ? hikari.getHikariPoolMXBean() : null;
    }

    /** Tomcat 실제 busy 스레드. 얻지 못하면 -1(미측정). */
    private int tomcatBusyThreads() {
        try {
            if (applicationContext instanceof ServletWebServerApplicationContext servletContext
                    && servletContext.getWebServer() instanceof TomcatWebServer tomcat
                    && tomcat.getTomcat().getConnector().getProtocolHandler()
                            .getExecutor() instanceof ThreadPoolExecutor executor) {
                return executor.getActiveCount();
            }
        } catch (RuntimeException e) {
            // 측정 실패가 벤치를 죽이지 않게 한다.
        }
        return -1;
    }

    private ResourceProbe startProbe() {
        // B 실험에서 측정기가 application Hikari pool 을 잠식하지 않도록
        // MySQL 상태 조회는 별도의 비풀링 JDBC connection 1개로 분리한다.
        MySqlStatusMonitor mysqlMonitor = MySqlStatusMonitor.open(dataSource);
        ResourceProbe probe = new ResourceProbe(hikariPool(), this::tomcatBusyThreads, mysqlMonitor, PROBE_MS);
        probe.start();
        return probe;
    }

    // SHOW 계열은 드라이버/서버에 따라 바인드 파라미터를 거부할 수 있어 상수만 인라인한다.
    // name 은 코드 내부 상수만 들어오며, 아래 검증으로 주입 여지를 막는다.
    private static String safeName(String name) {
        if (!name.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("허용되지 않는 변수명: " + name);
        }
        return name;
    }

    private String showVariable(String name) {
        return jdbc.query("SHOW VARIABLES LIKE '" + safeName(name) + "'",
                        (rs, rowNum) -> rs.getString(2))
                .stream().findFirst().orElse("0");
    }

    /** 라운드 전후로 찍어 델타를 낸다. 처리량 정체의 원인을 "락"이라 부르려면 이 값이 근거가 된다. */
    private Map<String, Long> innodbLockStatus() {
        Map<String, Long> values = new LinkedHashMap<>();
        jdbc.query("SHOW GLOBAL STATUS LIKE 'Innodb_row_lock%'",
                (rs, rowNum) -> Map.entry(rs.getString(1), rs.getString(2)))
                .forEach(entry -> {
                    try {
                        values.put(entry.getKey(), Long.parseLong(entry.getValue()));
                    } catch (NumberFormatException ignored) {
                        // 숫자가 아닌 항목은 무시
                    }
                });
        List<Map.Entry<String, String>> deadlockStatus = jdbc.query(
                "SHOW GLOBAL STATUS LIKE 'Innodb_deadlocks'",
                (rs, rowNum) -> Map.entry(rs.getString(1), rs.getString(2))
        );
        if (!deadlockStatus.isEmpty()) {
            deadlockStatus.forEach(entry -> values.put(entry.getKey(), Long.parseLong(entry.getValue())));
            return values;
        }

        try {
            Long deadlockCount = jdbc.queryForObject("""
                    SELECT SUM_ERROR_RAISED
                    FROM performance_schema.events_errors_summary_global_by_error
                    WHERE ERROR_NAME = 'ER_LOCK_DEADLOCK'
                    """, Long.class);
            values.put("Innodb_deadlocks", deadlockCount == null ? 0L : deadlockCount);
        } catch (DataAccessException ignored) {
            // MySQL 8.4 no longer exposes Innodb_deadlocks. The debug runner grants this
            // disposable test user read access to the performance_schema fallback.
            values.put("Innodb_deadlocks", -1L);
        }
        return values;
    }

    private static Map<String, Long> delta(Map<String, Long> before, Map<String, Long> after) {
        Map<String, Long> result = new LinkedHashMap<>();
        after.forEach((key, value) -> {
            long previous = before.getOrDefault(key, value < 0 ? -1L : 0L);
            result.put(key, value < 0 || previous < 0 ? -1L : value - previous);
        });
        return result;
    }

    // ------------------------------------------------------------ invariants

    private void verifyInvariants(RoundResult result) {
        int confirmedTotal = 0;
        for (long marketId = 1; marketId <= MARKETS; marketId++) {
            int confirmed = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM market_prediction WHERE market_id = ? AND status = 'CONFIRMED'",
                    Integer.class, marketId);
            BigDecimal priceSum = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(current_price),0) FROM market_option WHERE market_id = ?",
                    BigDecimal.class, marketId);
            BigDecimal realPoolSum = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(real_pool_amount),0) FROM market_option WHERE market_id = ?",
                    BigDecimal.class, marketId);
            BigDecimal totalPool = jdbc.queryForObject(
                    "SELECT total_pool FROM market WHERE id = ?", BigDecimal.class, marketId);
            int historyRows = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM market_price_history WHERE market_id = ?",
                    Integer.class, marketId);

            BigDecimal expectedPool = BET.multiply(new BigDecimal(confirmed));

            assertThat(priceSum.subtract(BigDecimal.ONE).abs())
                    .as("M4 market %d 가격 합 ≈ 1.0", marketId)
                    .isLessThanOrEqualTo(new BigDecimal("0.00000010"));
            assertThat(realPoolSum).as("M4 market %d real pool 합", marketId)
                    .isEqualByComparingTo(expectedPool);
            assertThat(totalPool).as("M4 market %d total_pool", marketId)
                    .isEqualByComparingTo(expectedPool);
            assertThat(historyRows).as("M4 market %d price_history 누락/중복 0", marketId)
                    .isEqualTo(confirmed * OPTION_COUNT);
            confirmedTotal += confirmed;
        }
        result.confirmedInDb = confirmedTotal;
        assertThat(result.finished()).as("시간 내 종료").isTrue();
        // 주의: 실제 Member-Point 연동 시 POINT_UNKNOWN 도 2xx 로 나간다. 그때는 이 단언을 응답 body 의
        // status 로 분류하도록 고칠 것 (지금은 fake client 라 항상 CONFIRMED).
        assertThat(confirmedTotal).as("CONFIRMED 총건수 = 2xx 응답 수")
                .isEqualTo(result.recorder().successCount());
    }

    // --------------------------------------------------------------- seeding

    private void resetMarketData() {
        jdbc.update("DELETE FROM market_price_history");
        jdbc.update("DELETE FROM market_reputation_update");
        jdbc.update("DELETE FROM market_refund_detail");
        jdbc.update("DELETE FROM market_void");
        jdbc.update("DELETE FROM market_settlement_detail");
        jdbc.update("DELETE FROM market_settlement");
        jdbc.update("DELETE FROM market_prediction");
        jdbc.update("DELETE FROM market_comment");
        jdbc.update("DELETE FROM market_option");
        jdbc.update("DELETE FROM market");

        LocalDateTime now = LocalDateTime.now();
        for (long marketId = 1; marketId <= MARKETS; marketId++) {
            jdbc.update("""
                    INSERT INTO market (
                        id, title, category, answer_type, judge_data_source, judge_criteria, judge_date,
                        status, close_at, total_pool, initial_virtual_liquidity, created_by, created_at, updated_at
                    ) VALUES (?, ?, 'PRICE_INDEX', 'YES_NO', 'BENCH', 'BENCH',
                              ?, 'ACTIVE', ?, 0.00, 200.00, 1, ?, ?)
                    """, marketId, "Benchmark Market " + marketId,
                    LocalDate.now(), now.plusDays(1), now, now);
            seedOption(marketId, optionId(marketId, false), "YES", "예", 1, now);
            seedOption(marketId, optionId(marketId, true), "NO", "아니오", 2, now);
        }
    }

    private void seedOption(long marketId, long optionId, String code, String text, int order, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO market_option (
                    id, market_id, option_code, option_text, display_order,
                    virtual_pool_amount, real_pool_amount, total_contract_quantity,
                    current_price, prediction_count, is_result, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 100.00, 0.00, 0.00000000, 0.50000000, 0, FALSE, ?, ?)
                """, optionId, marketId, code, text, order, now, now);
    }

    // ---------------------------------------------------------------- report

    private void printEnvironmentHeader() {
        System.out.printf("""

                ============ [벤치 v3] 예측 참여 경로 ============
                 부하 모델            : %s
                 확정 측정 모드       : %s   %s
                 동시성 C             : %d            (closed-loop 전용)
                 도착률 / 지속        : %d req/s, %ds  (open-loop 전용)
                 레벨당 요청          : %d  (워밍업 %d 제외), 라운드 %d
                 마켓 수              : %d
                 Hikari max pool      : %d
                 Tomcat max threads   : %s
                 MySQL max_connections: %s
                 Member-Point 지연    : %d ms (fake)
                 CPU 코어 / 최대 힙   : %d / %d MB
                 Java / OS            : %s / %s %s
                ================================================%n""",
                MODE,
                CONFIRM_MODE,
                asyncConfirm()
                        ? "(Observer 폴링 " + CONFIRM_POLL_MS + "ms, 중점 보정)"
                        : "(M2 = 요청→응답, 상한값. Observer 미사용)",
                CONCURRENCY, RATE, DURATION_SEC, REQUESTS, WARMUP, ROUNDS, MARKETS,
                dataSource instanceof HikariDataSource h ? h.getMaximumPoolSize() : -1,
                System.getProperty("bench.tomcatThreads", "200"),
                showVariable("max_connections"),
                BenchClientConfig.spendDelayMs(),
                Runtime.getRuntime().availableProcessors(),
                Runtime.getRuntime().maxMemory() / (1024 * 1024),
                System.getProperty("java.version"),
                System.getProperty("os.name"), System.getProperty("os.arch"));
    }

    private static String m2Label() {
        if (asyncConfirm()) {
            return "(의도→확정 관측)";
        }
        if ("open".equalsIgnoreCase(MODE)) {
            return "(의도→응답, 상한)";
        }
        return "(= M1-service, 상한)";
    }

    private void printRound(int round, RoundResult r) {
        Percentiles service = Percentiles.of(r.serviceLatencies());
        Percentiles offered = Percentiles.of(r.offeredLatencies());
        Percentiles e2e = Percentiles.of(r.endToEndLatencies());

        System.out.printf("%n [round %d] ok=%d fail=%d confirmedInDb=%d 생성기포화=%d%n",
                round, r.recorder().successCount(), r.recorder().failureCount(),
                r.confirmedInDb, r.generatorSaturated());
        System.out.printf("   M1-service (송신→응답)   p50=%.1f p95=%.1f p99=%.1f max=%.1f ms%n",
                service.p50(), service.p95(), service.p99(), service.max());
        if ("open".equalsIgnoreCase(MODE)) {
            System.out.printf("   M1-offered (의도→응답)   p50=%.1f p95=%.1f p99=%.1f max=%.1f ms  ← CO 보정%n",
                    offered.p50(), offered.p95(), offered.p99(), offered.max());
        }
        System.out.printf("   M2 %-22s p50=%.1f p95=%.1f p99=%.1f max=%.1f ms%n",
                m2Label(), e2e.p50(), e2e.p95(), e2e.p99(), e2e.max());
        System.out.printf("   Admission TPS    = %.1f /s%n", r.admissionTps());
        System.out.printf("   Confirmation TPS = %.1f /s%s%n", r.confirmationTps(),
                asyncConfirm() ? " (관측 확정 기준)" : " (응답 완료 기준 보수적 하한)");
        System.out.printf("   자원  Hikari active max=%d / awaiting max=%d | Tomcat busy max=%d"
                        + " | MySQL Threads_running max=%d%n",
                r.resources().maxActive(), r.resources().maxAwaiting(),
                r.resources().maxTomcatBusy(), r.resources().maxThreadsRunning());
        System.out.printf("   InnoDB 라운드 델타  row_lock_waits=%d  row_lock_time=%d ms%n",
                r.lockDelta().getOrDefault("Innodb_row_lock_waits", 0L),
                r.lockDelta().getOrDefault("Innodb_row_lock_time", 0L));
        System.out.printf("                        deadlocks=%s%n",
                counter(r.lockDelta(), "Innodb_deadlocks"));
        if (DEADLOCK_DEBUG) {
            System.out.printf("   Deadlock debug: requests=%d success=%d failure=%d failureRate=%.1f%%%n",
                    r.recorder().totalCount(), r.recorder().successCount(), r.recorder().failureCount(),
                    r.recorder().failureRate());
            System.out.println("   Failure classification: " + r.recorder().failureCategories());
            System.out.printf("   DB delta: deadlocks=%s row_lock_waits=%d row_lock_time=%d ms%n",
                    counter(r.lockDelta(), "Innodb_deadlocks"),
                    r.lockDelta().getOrDefault("Innodb_row_lock_waits", 0L),
                    r.lockDelta().getOrDefault("Innodb_row_lock_time", 0L));
        }
        if (!r.recorder().failureSamples().isEmpty()) {
            System.out.println("   실패 샘플: " + r.recorder().failureSamples());
        }
    }

    private static String counter(Map<String, Long> values, String key) {
        long value = values.getOrDefault(key, -1L);
        return value < 0 ? "n/a" : Long.toString(value);
    }

    private void printSummary(List<RoundResult> results) {
        double[] m1p50 = results.stream()
                .mapToDouble(r -> Percentiles.of(r.serviceLatencies()).p50()).sorted().toArray();
        double[] m1p99 = results.stream()
                .mapToDouble(r -> Percentiles.of(r.serviceLatencies()).p99()).sorted().toArray();
        double[] m2p99 = results.stream()
                .mapToDouble(r -> Percentiles.of(r.endToEndLatencies()).p99()).sorted().toArray();
        double[] adm = results.stream().mapToDouble(RoundResult::admissionTps).sorted().toArray();
        double[] conf = results.stream().mapToDouble(RoundResult::confirmationTps).sorted().toArray();
        int[] hikariAwait = results.stream().mapToInt(r -> r.resources().maxAwaiting()).sorted().toArray();
        int[] tomcatBusy = results.stream().mapToInt(r -> r.resources().maxTomcatBusy()).sorted().toArray();
        long[] lockWaits = results.stream()
                .mapToLong(r -> r.lockDelta().getOrDefault("Innodb_row_lock_waits", 0L)).sorted().toArray();
        long[] lockTime = results.stream()
                .mapToLong(r -> r.lockDelta().getOrDefault("Innodb_row_lock_time", 0L)).sorted().toArray();

        int mid = results.size() / 2;

        System.out.printf("""

                ---- 결과 요약 (round median) ----
                | mode | confirm | C | rate | markets | pool | M1 p50 | M1 p99 | M2 p99 | Adm TPS | Conf TPS | HikariAwait max | TomcatBusy max | lock_waits | lock_time(ms) |
                |------|---------|---|------|---------|------|--------|--------|--------|---------|----------|-----------------|----------------|------------|---------------|
                | %s | %s | %d | %d | %d | %d | %.1f | %.1f | %.1f | %.1f | %.1f | %d | %d | %d | %d |

                ※ 모든 요약 값은 round별 값의 median이다. 서로 다른 round의 값이나 합계를 섞지 않는다.
                ※ sync/closed M2는 M1-service 응답 시점 상한, sync/open M2는 M1-offered 응답 시점 상한이다.
                ※ sync Confirmation TPS는 실제 확정이 응답보다 먼저 끝나므로 응답 완료 기준의 보수적 하한이다.
                ※ 1/throughput 파생값은 이 표에서 계산하지 않는다. A sweep에서 plateau가 확인된 뒤에만 사후 분석한다.
                %n""",
                MODE, CONFIRM_MODE, CONCURRENCY, RATE, MARKETS,
                dataSource instanceof HikariDataSource h ? h.getMaximumPoolSize() : -1,
                m1p50[mid], m1p99[mid], m2p99[mid], adm[mid], conf[mid],
                hikariAwait[mid], tomcatBusy[mid], lockWaits[mid], lockTime[mid]);
    }

    // ------------------------------------------------------------- recording

    private static final class Sample {
        private final String key;
        private final long intendedNanos;
        private final long sendNanos;
        private final long responseNanos;

        private Sample(String key, long intendedNanos, long sendNanos, long responseNanos) {
            this.key = key;
            this.intendedNanos = intendedNanos;
            this.sendNanos = sendNanos;
            this.responseNanos = responseNanos;
        }
    }

    private static final class Recorder {
        private final boolean record;
        private final ConcurrentLinkedQueue<Sample> samples = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<String> failureSamples = new ConcurrentLinkedQueue<>();
        private final ConcurrentHashMap<String, AtomicInteger> failureCategories = new ConcurrentHashMap<>();
        private final AtomicInteger success = new AtomicInteger();
        private final AtomicInteger failure = new AtomicInteger();
        private final AtomicLong lastResponse = new AtomicLong();

        private Recorder(boolean record) {
            this.record = record;
        }

        private void record(long marketId, long memberId, long intended, long send, long response,
                            boolean ok, String failReason, String failureCategory) {
            lastResponse.accumulateAndGet(response, Math::max);
            if (ok) {
                success.incrementAndGet();
                if (record) {
                    samples.add(new Sample(marketId + ":" + memberId, intended, send, response));
                }
            } else {
                failure.incrementAndGet();
                if (failureSamples.size() < 5 && failReason != null) {
                    failureSamples.add(failReason);
                }
                if (failureCategory != null) {
                    failureCategories.computeIfAbsent(failureCategory, ignored -> new AtomicInteger())
                            .incrementAndGet();
                }
            }
        }

        private int successCount() {
            return success.get();
        }

        private int failureCount() {
            return failure.get();
        }

        private long lastResponseNanos() {
            return lastResponse.get();
        }

        private List<String> failureSamples() {
            return new ArrayList<>(failureSamples);
        }

        private int totalCount() {
            return successCount() + failureCount();
        }

        private double failureRate() {
            return totalCount() == 0 ? 0.0 : failureCount() * 100.0 / totalCount();
        }

        private Map<String, Integer> failureCategories() {
            Map<String, Integer> result = new TreeMap<>();
            failureCategories.forEach((category, count) -> result.put(category, count.get()));
            return result;
        }
    }

    /**
     * 확정 관측 전담 스레드(async 모드 전용). 부하 생성기와 분리되어 유입량에 영향을 주지 않는다.
     * 확정 시각은 "발견한 스캔 시각"이 아니라 <b>직전 스캔과 이번 스캔의 중점</b>으로 찍는다.
     */
    private static final class ConfirmObserver {
        private final JdbcTemplate jdbc;
        private final long pollMs;
        private final Map<String, Long> firstSeen = new ConcurrentHashMap<>();
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final Thread thread;
        private volatile long previousScanNanos;

        private ConfirmObserver(JdbcTemplate jdbc, long pollMs) {
            this.jdbc = jdbc;
            this.pollMs = pollMs;
            this.previousScanNanos = System.nanoTime();
            this.thread = new Thread(this::loop, "confirm-observer");
            this.thread.setDaemon(true);
        }

        private static ConfirmObserver start(JdbcTemplate jdbc, long pollMs) {
            ConfirmObserver observer = new ConfirmObserver(jdbc, pollMs);
            observer.thread.start();
            return observer;
        }

        private void loop() {
            while (running.get()) {
                scan();
                try {
                    Thread.sleep(pollMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        private void scan() {
            try {
                long scanAt = System.nanoTime();
                // 확정은 (직전 스캔, 이번 스캔] 안에서 일어났다. 균등분포 가정 시 중점으로 평균 편향을 줄인다.
                long estimate = previousScanNanos + (scanAt - previousScanNanos) / 2;
                jdbc.query("SELECT market_id, member_id FROM market_prediction WHERE status = 'CONFIRMED'",
                                (rs, rowNum) -> rs.getLong(1) + ":" + rs.getLong(2))
                        .forEach(key -> firstSeen.putIfAbsent(key, estimate));
                previousScanNanos = scanAt;
            } catch (RuntimeException e) {
                // 관측 실패가 측정을 죽이지 않게 한다.
            }
        }

        private void drain(int expected, long deadlineNanos) throws InterruptedException {
            while (firstSeen.size() < expected && System.nanoTime() < deadlineNanos) {
                Thread.sleep(pollMs);
            }
        }

        private Map<String, Long> stopAndCollect() {
            running.set(false);
            thread.interrupt();
            scan();
            return firstSeen;
        }
    }

    /**
     * application Hikari 와 분리된 MySQL 상태 조회 connection.
     * 계측용 쿼리가 pool=10인 B 실험의 애플리케이션 커넥션을 빼앗지 않게 한다.
     */
    private static final class MySqlStatusMonitor implements AutoCloseable {
        private final Connection connection;

        private MySqlStatusMonitor(Connection connection) {
            this.connection = connection;
        }

        private static MySqlStatusMonitor open(DataSource dataSource) {
            if (!(dataSource instanceof HikariDataSource hikari)) {
                return new MySqlStatusMonitor(null);
            }
            try {
                String jdbcUrl = hikari.getJdbcUrl();
                String username = hikari.getUsername();
                String password = hikari.getPassword();
                if (jdbcUrl == null || username == null) {
                    return new MySqlStatusMonitor(null);
                }
                Connection connection = DriverManager.getConnection(
                        jdbcUrl, username, password == null ? "" : password);
                return new MySqlStatusMonitor(connection);
            } catch (SQLException e) {
                return new MySqlStatusMonitor(null);
            }
        }

        private long threadsRunning() {
            if (connection == null) {
                return -1;
            }
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SHOW GLOBAL STATUS LIKE 'Threads_running'")) {
                return rs.next() ? Long.parseLong(rs.getString(2)) : -1;
            } catch (SQLException | NumberFormatException e) {
                return -1;
            }
        }

        @Override
        public void close() {
            if (connection == null) {
                return;
            }
            try {
                connection.close();
            } catch (SQLException ignored) {
                // 계측 connection 종료 실패가 벤치 결과를 덮지 않게 한다.
            }
        }
    }

    /** 라운드 동안 Hikari / Tomcat / MySQL 상태를 샘플링한다. 정체 원인 규명용. */
    private static final class ResourceProbe {
        private final HikariPoolMXBean pool;
        private final java.util.function.IntSupplier tomcatBusy;
        private final MySqlStatusMonitor mysqlMonitor;
        private final long intervalMs;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final Thread thread;
        private volatile int maxActive = -1;
        private volatile int maxAwaiting = -1;
        private volatile int maxTomcatBusy = -1;
        private volatile long maxThreadsRunning = -1;

        private ResourceProbe(HikariPoolMXBean pool, java.util.function.IntSupplier tomcatBusy,
                              MySqlStatusMonitor mysqlMonitor, long intervalMs) {
            this.pool = pool;
            this.tomcatBusy = tomcatBusy;
            this.mysqlMonitor = mysqlMonitor;
            this.intervalMs = intervalMs;
            this.thread = new Thread(this::loop, "resource-probe");
            this.thread.setDaemon(true);
        }

        private void start() {
            thread.start();
        }

        private void loop() {
            while (running.get()) {
                sample();
                try {
                    Thread.sleep(intervalMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        private void sample() {
            try {
                if (pool != null) {
                    int active = pool.getActiveConnections();
                    int awaiting = pool.getThreadsAwaitingConnection();
                    maxActive = Math.max(maxActive, active);
                    maxAwaiting = Math.max(maxAwaiting, awaiting);
                }

                int busy = tomcatBusy.getAsInt();
                if (busy >= 0) {
                    maxTomcatBusy = Math.max(maxTomcatBusy, busy);
                }

                long threadsRunning = mysqlMonitor.threadsRunning();
                if (threadsRunning >= 0) {
                    maxThreadsRunning = Math.max(maxThreadsRunning, threadsRunning);
                }
            } catch (RuntimeException e) {
                // 계측 실패는 벤치 본체를 죽이지 않는다. 미측정 값은 -1로 남는다.
            }
        }

        private ResourceSnapshot stop() {
            running.set(false);
            thread.interrupt();
            try {
                thread.join(Math.max(1_000L, intervalMs * 2));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (!thread.isAlive()) {
                sample();
            }
            mysqlMonitor.close();
            return new ResourceSnapshot(maxActive, maxAwaiting, maxTomcatBusy, maxThreadsRunning);
        }
    }

    private record ResourceSnapshot(int maxActive, int maxAwaiting, int maxTomcatBusy, long maxThreadsRunning) {
        private static ResourceSnapshot empty() {
            return new ResourceSnapshot(-1, -1, -1, -1);
        }
    }

    // ---------------------------------------------------------------- result

    private static final class RoundResult {
        private final boolean finished;
        private final Recorder recorder;
        private final Map<String, Long> confirmedAt;
        private final ResourceSnapshot resources;
        private final Map<String, Long> lockDelta;
        private final long wallStart;
        private final long lastResponse;
        private final int generatorSaturated;
        private int confirmedInDb;

        private RoundResult(boolean finished, Recorder recorder, Map<String, Long> confirmedAt,
                            ResourceSnapshot resources, Map<String, Long> lockDelta,
                            long wallStart, long lastResponse, int generatorSaturated) {
            this.finished = finished;
            this.recorder = recorder;
            this.confirmedAt = confirmedAt;
            this.resources = resources;
            this.lockDelta = lockDelta;
            this.wallStart = wallStart;
            this.lastResponse = lastResponse;
            this.generatorSaturated = generatorSaturated;
        }

        private boolean finished() {
            return finished;
        }

        private Recorder recorder() {
            return recorder;
        }

        private ResourceSnapshot resources() {
            return resources;
        }

        private Map<String, Long> lockDelta() {
            return lockDelta;
        }

        private int generatorSaturated() {
            return generatorSaturated;
        }

        private long[] serviceLatencies() {
            return recorder.samples.stream().mapToLong(s -> s.responseNanos - s.sendNanos).toArray();
        }

        private long[] offeredLatencies() {
            return recorder.samples.stream().mapToLong(s -> s.responseNanos - s.intendedNanos).toArray();
        }

        /**
         * sync(AS-IS): 확정이 응답보다 먼저 커밋되므로 요청→응답을 확정 시간의 상한으로 쓴다.
         * async(EDA): Observer 가 관측한 확정 시각(중점 보정) 기준.
         */
        private long[] endToEndLatencies() {
            if (!asyncConfirm()) {
                return offeredLatencies();
            }
            return recorder.samples.stream()
                    .filter(s -> confirmedAt.containsKey(s.key))
                    .mapToLong(s -> confirmedAt.get(s.key) - s.intendedNanos)
                    .toArray();
        }

        /** 2xx 수락 처리량. TO-BE 에서 이걸 성능으로 읽으면 안 된다. */
        private double admissionTps() {
            return recorder.successCount() * 1_000_000_000.0 / Math.max(lastResponse - wallStart, 1);
        }

        /**
         * 확정 처리량 비교축. sync에서는 마지막 실제 확정 시각을 직접 알 수 없으므로
         * 마지막 HTTP 응답 시각을 사용한 보수적 하한이다. async에서는 Observer 관측 확정 기준이다.
         */
        private double confirmationTps() {
            if (!asyncConfirm()) {
                return admissionTps();
            }
            long lastConfirm = confirmedAt.values().stream()
                    .mapToLong(Long::longValue).max().orElse(lastResponse);
            return confirmedAt.size() * 1_000_000_000.0 / Math.max(lastConfirm - wallStart, 1);
        }
    }

    private record Percentiles(double p50, double p95, double p99, double max) {

        private static Percentiles of(long[] nanos) {
            if (nanos.length == 0) {
                return new Percentiles(0, 0, 0, 0);
            }
            long[] sorted = nanos.clone();
            Arrays.sort(sorted);
            return new Percentiles(at(sorted, 0.50), at(sorted, 0.95), at(sorted, 0.99),
                    sorted[sorted.length - 1] / 1_000_000.0);
        }

        private static double at(long[] sorted, double q) {
            int rank = (int) Math.ceil(q * sorted.length);
            return sorted[Math.min(Math.max(rank - 1, 0), sorted.length - 1)] / 1_000_000.0;
        }
    }

    @TestConfiguration
    static class BenchClientConfig {

        private static long spendDelayMs() {
            return Long.getLong("bench.spendDelayMs", 0L);
        }

        @Bean
        @Primary
        MemberPointClient benchMemberPointClient() {
            return new MemberPointClient() {
                @Override
                public void spend(PointSpendCommand command) {
                    sleep();
                }

                @Override
                public MemberPointTransactionStatusResponse getTransactionStatus(String idempotencyKey) {
                    sleep();
                    return null;
                }

                @Override
                public MemberPointTransactionStatusResponse fenceSpend(PointSpendCommand command) {
                    throw new UnsupportedOperationException("벤치는 예측 참여 경로만 측정한다");
                }

                @Override
                public MemberPointSettlementBatchResponse settleMarketRewards(
                        String batchIdempotencyKey, MemberPointSettlementBatchRequest request) {
                    throw new UnsupportedOperationException("벤치는 예측 참여 경로만 측정한다");
                }

                @Override
                public MemberPointRefundBatchResponse refundMarketPredictions(
                        String batchIdempotencyKey, MemberPointRefundBatchRequest request) {
                    throw new UnsupportedOperationException("벤치는 예측 참여 경로만 측정한다");
                }

                private void sleep() {
                    long ms = spendDelayMs();
                    if (ms <= 0) {
                        return;
                    }
                    try {
                        Thread.sleep(ms);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            };
        }
    }

    private static int intProp(String key, int defaultValue) {
        return Integer.getInteger(key, defaultValue);
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 120 ? value : value.substring(0, 120);
    }
}
