package com.todongsan.marketservice.market.type;

/** 정산 지급 요청 이벤트 형식 (ASYNC_POINT_FLOW.md §18.3 C6). */
public enum SettlementRequestFormat {
    /** 승자 1명 = 이벤트 1개 (v1) */
    ITEM,
    /** 승자 최대 chunk-size명 = 이벤트 1개 (v2) */
    CHUNK
}
