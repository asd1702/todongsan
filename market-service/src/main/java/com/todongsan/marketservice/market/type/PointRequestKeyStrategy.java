package com.todongsan.marketservice.market.type;

/** market.point-requests 토픽의 Kafka message key 전략. */
public enum PointRequestKeyStrategy {
    MEMBER_ID,
    MARKET_ID
}
