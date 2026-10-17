package com.todongsan.marketservice.market.type;

/** Member-Point 포인트 적립 요청 전송 방식. */
public enum PointTransportMode {
    /** Feign 배치 API 동기 호출 */
    SYNC,
    /** outbox + Debezium CDC + Kafka */
    ASYNC
}
