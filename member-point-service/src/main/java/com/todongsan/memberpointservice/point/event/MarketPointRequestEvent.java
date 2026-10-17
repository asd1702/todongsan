package com.todongsan.memberpointservice.point.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * market.point-requests 이벤트 (contracts/events/market.point-requests.v1.schema.json).
 * 모르는 필드는 무시한다 (스키마 선택 필드 추가 호환).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MarketPointRequestEvent(
        String eventId,
        String eventType,
        Integer schemaVersion,
        String occurredAt,
        String producer,
        Data data
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(
            Long marketId,
            Long predictionId,
            Long memberId,
            BigDecimal amount,
            String referenceType,
            Long referenceId,
            String reason,
            String idempotencyKey
    ) {
    }
}
