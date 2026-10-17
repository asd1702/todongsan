package com.todongsan.marketservice.market.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * memberpoint.point-results 이벤트 (contracts/events/memberpoint.point-results.v1.schema.json).
 * 모르는 필드는 무시한다 (스키마 선택 필드 추가 호환).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PointResultEvent(
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
