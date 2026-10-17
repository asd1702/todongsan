package com.todongsan.marketservice.market.type;

/**
 * Market → Member-Point 포인트 적립 요청 이벤트 타입.
 * 업무 흐름마다 토픽이 다르다. Debezium이 outbox `aggregate_type`으로 `market.{aggregateType}` 토픽에 발행한다 (ASYNC_POINT_FLOW.md §8.1 E5).
 * 스키마: contracts/events/market.{aggregateType}.v1.schema.json
 */
public enum PointRequestEventType {
    MARKET_REFUND_REQUESTED("refund-requests"),
    MARKET_SETTLEMENT_PAYOUT_REQUESTED("settlement-payout-requests");

    private final String aggregateType;

    PointRequestEventType(String aggregateType) {
        this.aggregateType = aggregateType;
    }

    public String aggregateType() {
        return aggregateType;
    }
}
