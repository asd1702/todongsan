package com.todongsan.marketservice.market.service;

/**
 * 결과 1건 반영 결과.
 * parentId는 완료 판정 대상(환불: market_void.id, 정산: market_settlement.id). detail이 없으면 null.
 */
public record AppliedPointResult(PointResultApplyOutcome outcome, Long parentId) {

    static AppliedPointResult detailNotFound() {
        return new AppliedPointResult(PointResultApplyOutcome.DETAIL_NOT_FOUND, null);
    }
}
