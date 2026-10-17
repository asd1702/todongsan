package com.todongsan.marketservice.market.type;

/** 포인트 적립 요청 토픽(market.refund-requests, market.settlement-payout-requests)의 Kafka message key 전략. */
public enum PointRequestKeyStrategy {
    MEMBER_ID,
    MARKET_ID
}
