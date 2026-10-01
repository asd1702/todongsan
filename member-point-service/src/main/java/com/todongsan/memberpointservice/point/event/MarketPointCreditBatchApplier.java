package com.todongsan.memberpointservice.point.event;

import com.todongsan.memberpointservice.global.exception.ErrorCode;
import com.todongsan.memberpointservice.global.util.RequestHashUtil;
import com.todongsan.memberpointservice.point.dto.response.BatchItemResult;
import com.todongsan.memberpointservice.point.entity.PointHistoryType;
import com.todongsan.memberpointservice.point.entity.PointReferenceType;
import com.todongsan.memberpointservice.point.entity.PointTransactionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 포인트 적립 요청 배치를 트랜잭션 1개로 처리한다 (ASYNC_POINT_FLOW.md §17).
 * 판정 규칙은 건 단위 경로(PointInternalServiceImpl.processEarnItem)와 같고, 차이는 DB 왕복과 커밋 횟수뿐이다.
 *
 * ① 기존 이력을 IN으로 한 번에 조회 ② 회원 row를 ID 오름차순으로 잠그고 잔액 조회
 * ③ 받은 순서대로 판정하며 회원별 잔액을 누적 계산 (같은 회원이 여러 번 나와도 이력마다 정확한 처리 후 잔액)
 * ④ 잔액 UPDATE·이력 INSERT·결과 outbox INSERT를 각각 JDBC batch로 기록
 */
@Component
@RequiredArgsConstructor
public class MarketPointCreditBatchApplier {

    private static final String STATUS_PROCESSED = "PROCESSED";
    private static final String STATUS_ALREADY_PROCESSED = "ALREADY_PROCESSED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String INSERT_HISTORY_SQL = """
            INSERT INTO point_history (member_id, type, amount, balance_snapshot, reason, reference_type, reference_id,
                                       idempotency_key, request_hash, status, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String UPDATE_BALANCE_SQL = "UPDATE member SET point_balance = point_balance + ? WHERE id = ?";

    private final JdbcTemplate jdbcTemplate;
    private final PointResultOutboxWriter pointResultOutboxWriter;

    public record Item(MarketPointRequestEvent event, PointHistoryType type) {
    }

    @Transactional
    public List<BatchItemResult> applyBatch(List<Item> items) {
        Map<String, Recorded> recorded = findExisting(items);
        Map<Long, BigDecimal> balances = lockMembers(items);
        Map<Long, BigDecimal> increments = new LinkedHashMap<>();
        List<Object[]> histories = new ArrayList<>();
        List<BatchItemResult> results = new ArrayList<>(items.size());
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());

        for (Item item : items) {
            MarketPointRequestEvent.Data data = item.event().data();
            String requestHash = RequestHashUtil.compute(data.memberId(), item.type().name(), data.amount(),
                    data.referenceType(), data.referenceId());

            // 이미 기록된 키 (이전 배치 또는 같은 배치의 앞 건)
            Recorded prior = recorded.get(data.idempotencyKey());
            if (prior != null) {
                results.add(requestHash.equals(prior.requestHash())
                        ? result(data, STATUS_ALREADY_PROCESSED, null, prior.amount(), prior.balanceSnapshot())
                        : failed(data, ErrorCode.IDEMPOTENCY_KEY_CONFLICT));
                continue;
            }
            if (data.amount() == null || data.amount().compareTo(BigDecimal.ZERO) <= 0) {
                results.add(failed(data, ErrorCode.POINT_INVALID_AMOUNT));
                continue;
            }
            if (!isValidReferenceType(data.referenceType())) {
                results.add(failed(data, ErrorCode.POINT_INVALID_REFERENCE_TYPE));
                continue;
            }
            BigDecimal balance = balances.get(data.memberId());
            if (balance == null) {
                results.add(failed(data, ErrorCode.MEMBER_NOT_FOUND));
                continue;
            }

            BigDecimal amount = data.amount().setScale(2, RoundingMode.DOWN);
            BigDecimal snapshot = balance.add(amount);
            balances.put(data.memberId(), snapshot);
            increments.merge(data.memberId(), amount, BigDecimal::add);
            histories.add(new Object[]{
                    data.memberId(), item.type().name(), amount, snapshot, data.reason(),
                    data.referenceType(), data.referenceId(), data.idempotencyKey(), requestHash,
                    PointTransactionStatus.SUCCEEDED.name(), now, now
            });
            recorded.put(data.idempotencyKey(), new Recorded(requestHash, amount, snapshot));
            results.add(result(data, STATUS_PROCESSED, null, amount, snapshot));
        }

        if (!increments.isEmpty()) {
            jdbcTemplate.batchUpdate(UPDATE_BALANCE_SQL, increments.entrySet().stream()
                    .map(e -> new Object[]{e.getValue(), e.getKey()})
                    .toList());
            jdbcTemplate.batchUpdate(INSERT_HISTORY_SQL, histories);
        }
        pointResultOutboxWriter.appendAll(items.stream().map(Item::event).toList(), results);
        return results;
    }

    private Map<String, Recorded> findExisting(List<Item> items) {
        List<String> keys = items.stream().map(item -> item.event().data().idempotencyKey()).distinct().toList();
        Map<String, Recorded> recorded = new HashMap<>();
        new NamedParameterJdbcTemplate(jdbcTemplate).query("""
                        SELECT idempotency_key, request_hash, amount, balance_snapshot
                        FROM point_history WHERE idempotency_key IN (:keys)
                        """,
                new MapSqlParameterSource("keys", keys),
                rs -> {
                    recorded.put(rs.getString("idempotency_key"), new Recorded(
                            rs.getString("request_hash"), rs.getBigDecimal("amount"), rs.getBigDecimal("balance_snapshot")));
                });
        return recorded;
    }

    /** ID 오름차순으로 잠근다 (§17.4 M4). 건 단위 경로의 findById와 같이 탈퇴 여부는 보지 않는다. */
    private Map<Long, BigDecimal> lockMembers(List<Item> items) {
        List<Long> memberIds = items.stream().map(item -> item.event().data().memberId()).distinct().sorted().toList();
        Map<Long, BigDecimal> balances = new HashMap<>();
        new NamedParameterJdbcTemplate(jdbcTemplate).query(
                "SELECT id, point_balance FROM member WHERE id IN (:ids) ORDER BY id FOR UPDATE",
                new MapSqlParameterSource("ids", memberIds),
                rs -> {
                    balances.put(rs.getLong("id"), rs.getBigDecimal("point_balance"));
                });
        return balances;
    }

    private static boolean isValidReferenceType(String referenceType) {
        if (referenceType == null || referenceType.isBlank()) {
            return true;
        }
        try {
            PointReferenceType.valueOf(referenceType);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static BatchItemResult failed(MarketPointRequestEvent.Data data, ErrorCode errorCode) {
        return result(data, STATUS_FAILED, errorCode.getCode(), data.amount(), null);
    }

    private static BatchItemResult result(MarketPointRequestEvent.Data data, String status, String errorCode,
                                          BigDecimal amount, BigDecimal balanceSnapshot) {
        return BatchItemResult.builder()
                .predictionId(data.predictionId())
                .memberId(data.memberId())
                .status(status)
                .errorCode(errorCode)
                .amount(amount != null ? amount.toPlainString() : null)
                .balanceSnapshot(balanceSnapshot != null ? balanceSnapshot.toPlainString() : null)
                .build();
    }

    private record Recorded(String requestHash, BigDecimal amount, BigDecimal balanceSnapshot) {
    }
}
