package com.todongsan.memberpointservice.point.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * 정산 지급 요청 묶음 (contracts/events/market.settlement-payout-requests.v2.schema.json, ASYNC_POINT_FLOW.md §18).
 * 건마다 v1과 같은 모양의 {@link MarketPointRequestEvent}로 펼쳐 기존 적립 경로에 넣는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MarketPointChunkRequestEvent(
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
            Integer chunkNo,
            String referenceType,
            String reason,
            List<Item> items
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(
            Long predictionId,
            Long memberId,
            BigDecimal amount,
            Long referenceId,
            String idempotencyKey
    ) {
    }

    /** 펼친 건은 묶음의 eventId·schemaVersion을 가진다. 결과 기록은 schemaVersion으로 v2 묶음 결과를 고른다. */
    public List<MarketPointRequestEvent> toItemEvents() {
        return data.items().stream()
                .map(item -> new MarketPointRequestEvent(
                        eventId,
                        eventType,
                        schemaVersion,
                        occurredAt,
                        producer,
                        new MarketPointRequestEvent.Data(
                                data.marketId(),
                                item.predictionId(),
                                item.memberId(),
                                item.amount(),
                                data.referenceType(),
                                item.referenceId(),
                                data.reason(),
                                item.idempotencyKey()
                        )
                ))
                .toList();
    }
}
