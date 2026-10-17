package com.todongsan.marketservice.market.client.exception;

/**
 * Member-Point가 업무 오류(4xx, 예: IDEMPOTENCY_KEY_CONFLICT, MEMBER_NOT_FOUND)로 응답했다.
 * 예측 처리는 다른 결과 불명과 같지만(MemberPointExternalException), MP는 정상 응답한 것이므로
 * 차감 서킷 브레이커는 실패로 세지 않는다 (docs/architecture/SPEND_CIRCUIT_BREAKER.md B4 정정).
 */
public class MemberPointRejectedException extends MemberPointExternalException {

    public MemberPointRejectedException(String message) {
        super(message);
    }
}
