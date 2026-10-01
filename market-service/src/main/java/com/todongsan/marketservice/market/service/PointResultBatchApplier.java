package com.todongsan.marketservice.market.service;

import com.todongsan.marketservice.market.entity.MarketSettlementDetail;
import com.todongsan.marketservice.market.repository.MarketMapper;
import com.todongsan.marketservice.market.type.TransactionItemStatus;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Member-Point 결과 이벤트 여러 건을 트랜잭션 1개로 반영한다 (ASYNC_POINT_FLOW.md §16.1).
 *
 * 1. 배치에 등장한 market을 ID 오름차순으로 선잠금 (Q5)
 * 2. 받은 순서대로 순차 반영. detail 없음은 예외가 아닌 결과(DETAIL_NOT_FOUND)로 돌려준다 (Q2)
 * 3. 등장한 무효·정산마다 완료 판정 1회 → COMMIT 1회
 *
 * 정산 결과는 마켓마다 수천 건이 한꺼번에 오므로 묶음으로 처리한다 (§17.6).
 * detail을 한 번에 잠그고, 건 단위 경로(MarketSettlementTransactionService.applySettlementResultItem)와 같은 상태 전이 규칙을
 * 잠근 상태에 받은 순서대로 적용한 뒤, 실제로 바뀌는 UPDATE만 JDBC batch로 보낸다. 환불은 건수가 적어 건 단위 그대로다.
 */
@Service
@RequiredArgsConstructor
public class PointResultBatchApplier {

    public static final String MARKET_REFUND_REQUESTED = "MARKET_REFUND_REQUESTED";
    public static final String MARKET_SETTLEMENT_PAYOUT_REQUESTED = "MARKET_SETTLEMENT_PAYOUT_REQUESTED";

    /** 건 단위 경로의 updateRetrySettlementDetailStatus 조건과 같다. SUCCESS는 종결 상태다. */
    private static final Set<TransactionItemStatus> UPDATABLE_DETAIL_STATUSES =
            Set.of(TransactionItemStatus.FAILED, TransactionItemStatus.UNKNOWN, TransactionItemStatus.PENDING);
    private static final String FAIL_DETAIL_SQL = """
            UPDATE market_settlement_detail
            SET status = 'FAILED', fail_reason = ?, updated_at = ?
            WHERE id = ? AND status IN ('FAILED', 'UNKNOWN', 'PENDING')
            """;
    /** 성공은 모두 같은 값이라 IN 한 문장으로 쓴다. 상태 조건은 건 단위 경로와 같다 (안전장치). */
    private static final String SUCCEED_DETAILS_SQL = """
            UPDATE market_settlement_detail
            SET status = 'SUCCESS', fail_reason = NULL, updated_at = ?
            WHERE status IN ('FAILED', 'UNKNOWN', 'PENDING') AND id IN (%s)
            """;
    /** 지급액은 예측마다 달라 값 목록(VALUES ROW, MySQL 8.0.19+)과 JOIN해 한 문장으로 쓴다. */
    private static final String SETTLE_PREDICTIONS_SQL = """
            UPDATE market_prediction p
            JOIN (VALUES %s) AS v (id, settled_amount) ON p.id = v.id
            SET p.status = 'SETTLED', p.settled_amount = v.settled_amount, p.updated_at = ?
            WHERE p.status = 'CONFIRMED'
            """;

    private final MarketMapper marketMapper;
    private final MarketRefundTransactionService refundTransactionService;
    private final MarketSettlementTransactionService settlementTransactionService;
    private final JdbcTemplate jdbcTemplate;

    public record Item(String requestEventType, long marketId, String idempotencyKey, boolean credited, String errorCode) {
    }

    /** 입력 순서와 같은 순서로 결과를 돌려준다. */
    @Transactional
    public List<PointResultApplyOutcome> applyBatch(List<Item> items) {
        List<Long> marketIds = items.stream().map(Item::marketId).distinct().sorted().toList();
        Set<Long> lockedMarketIds = new HashSet<>(marketMapper.lockMarketsByIdsInOrder(marketIds));
        Map<String, MarketSettlementDetail> settlementDetails = lockSettlementDetails(items, lockedMarketIds);
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        Map<Long, MarketSettlementDetail> changedDetails = new LinkedHashMap<>();

        List<PointResultApplyOutcome> outcomes = new ArrayList<>(items.size());
        Set<Long> voidIds = new LinkedHashSet<>();
        Map<Long, Long> marketIdBySettlementId = new LinkedHashMap<>();
        for (Item item : items) {
            if (!lockedMarketIds.contains(item.marketId())) {
                outcomes.add(PointResultApplyOutcome.DETAIL_NOT_FOUND);
                continue;
            }
            AppliedPointResult applied = switch (item.requestEventType()) {
                case MARKET_REFUND_REQUESTED -> refundTransactionService.applyRefundResultItem(
                        item.idempotencyKey(), item.credited(), item.errorCode());
                case MARKET_SETTLEMENT_PAYOUT_REQUESTED -> applySettlement(
                        item, settlementDetails.get(item.idempotencyKey()), changedDetails);
                default -> throw new IllegalArgumentException("Unsupported requestEventType=" + item.requestEventType());
            };
            if (applied.parentId() != null) {
                if (MARKET_REFUND_REQUESTED.equals(item.requestEventType())) {
                    voidIds.add(applied.parentId());
                } else {
                    marketIdBySettlementId.put(applied.parentId(), item.marketId());
                }
            }
            outcomes.add(applied.outcome());
        }

        // 완료 판정이 반영된 상태를 세도록 먼저 기록한다
        writeSettlementResults(changedDetails.values(), now);
        voidIds.forEach(refundTransactionService::completeRefundIfDone);
        marketIdBySettlementId.forEach((settlementId, marketId) ->
                settlementTransactionService.completeSettlementIfDone(marketId, settlementId));
        return outcomes;
    }

    private Map<String, MarketSettlementDetail> lockSettlementDetails(List<Item> items, Set<Long> lockedMarketIds) {
        List<String> keys = items.stream()
                .filter(item -> MARKET_SETTLEMENT_PAYOUT_REQUESTED.equals(item.requestEventType()))
                .filter(item -> lockedMarketIds.contains(item.marketId()))
                .map(Item::idempotencyKey)
                .distinct()
                .toList();
        Map<String, MarketSettlementDetail> details = new HashMap<>();
        if (!keys.isEmpty()) {
            marketMapper.lockSettlementDetailsByIdempotencyKeys(keys)
                    .forEach(detail -> details.put(detail.getIdempotencyKey(), detail));
        }
        return details;
    }

    /**
     * 건 단위 경로와 같은 규칙을 잠근 상태에 적용한다. 같은 키가 배치에 여러 번 오면 앞 건의 결과 상태를 이어받는다.
     * DB에는 쓰지 않고 바뀐 detail만 모은다. 잠금을 잡고 있으므로 detail마다 최종 상태 한 번만 쓰면 건 단위 결과와 같다.
     */
    private static AppliedPointResult applySettlement(Item item, MarketSettlementDetail detail,
                                                      Map<Long, MarketSettlementDetail> changedDetails) {
        if (detail == null) {
            return AppliedPointResult.detailNotFound();
        }
        if (!UPDATABLE_DETAIL_STATUSES.contains(detail.getStatus())) {
            return new AppliedPointResult(PointResultApplyOutcome.SKIPPED, detail.getSettlementId());
        }
        if (item.credited()) {
            detail.setStatus(TransactionItemStatus.SUCCESS);
            detail.setFailReason(null);
        } else {
            detail.setStatus(TransactionItemStatus.FAILED);
            detail.setFailReason(MarketSettlementTransactionService.failureReason(item.errorCode(), "MEMBER_POINT_FAILED"));
        }
        changedDetails.put(detail.getId(), detail);
        return new AppliedPointResult(PointResultApplyOutcome.APPLIED, detail.getSettlementId());
    }

    /** 성공 detail은 IN 한 문장, 지급액은 VALUES JOIN 한 문장, 드문 실패는 건별로 쓴다 (§17.6). 성공한 detail만 예측을 정산한다. */
    private void writeSettlementResults(Collection<MarketSettlementDetail> details, Timestamp now) {
        List<MarketSettlementDetail> succeeded = details.stream()
                .filter(detail -> detail.getStatus() == TransactionItemStatus.SUCCESS)
                .toList();
        List<Object[]> failed = details.stream()
                .filter(detail -> detail.getStatus() == TransactionItemStatus.FAILED)
                .map(detail -> new Object[]{detail.getFailReason(), now, detail.getId()})
                .toList();
        if (!failed.isEmpty()) {
            jdbcTemplate.batchUpdate(FAIL_DETAIL_SQL, failed);
        }
        if (succeeded.isEmpty()) {
            return;
        }

        List<Object> detailArgs = new ArrayList<>(succeeded.size() + 1);
        detailArgs.add(now);
        succeeded.forEach(detail -> detailArgs.add(detail.getId()));
        jdbcTemplate.update(SUCCEED_DETAILS_SQL.formatted(placeholders(succeeded.size(), "?")), detailArgs.toArray());

        List<Object> predictionArgs = new ArrayList<>(succeeded.size() * 2 + 1);
        succeeded.forEach(detail -> {
            predictionArgs.add(detail.getPredictionId());
            predictionArgs.add(detail.getSettledAmount());
        });
        predictionArgs.add(now);
        jdbcTemplate.update(SETTLE_PREDICTIONS_SQL.formatted(placeholders(succeeded.size(), "ROW(?, ?)")),
                predictionArgs.toArray());
    }

    private static String placeholders(int count, String placeholder) {
        return String.join(", ", Collections.nCopies(count, placeholder));
    }
}
