package com.todongsan.marketservice.market.service;

/** Member-Point 결과 이벤트 반영 결과. */
public enum PointResultApplyOutcome {
    /** detail 상태가 바뀌었다 */
    APPLIED,
    /** 이미 종결된 detail(SUCCESS)이라 바뀌지 않았다 (재전달) */
    SKIPPED,
    /** 대상 detail이 없다 (데이터 불일치, 재시도 불가) */
    DETAIL_NOT_FOUND
}
