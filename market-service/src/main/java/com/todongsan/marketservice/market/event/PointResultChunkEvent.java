package com.todongsan.marketservice.market.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * 묶음 요청(v2)의 적립 결과 (contracts/events/memberpoint.point-results.v2.schema.json, ASYNC_POINT_FLOW.md §18).
 * MP 트랜잭션 1개의 건별 결과를 담는다. 건별 status는 v1 결과 eventType과 같은 의미다 (FAILED = POINT_CREDIT_FAILED).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PointResultChunkEvent(
        String eventId,
        String eventType,
        Integer schemaVersion,
        String occurredAt,
        String producer,
        Data data
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(
            String requestEventId,
            String requestEventType,
            Long marketId,
            List<Item> items
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(
            Long predictionId,
            Long memberId,
            String idempotencyKey,
            String amount,
            String status,
            String balanceSnapshot,
            String errorCode
    ) {
    }
}
