package com.todongsan.memberpointservice.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 공통
    INVALID_REQUEST("INVALID_REQUEST", "잘못된 요청입니다.", HttpStatus.BAD_REQUEST),
    VALIDATION_FAILED("VALIDATION_FAILED", "요청 값 검증에 실패했습니다.", HttpStatus.BAD_REQUEST),
    METHOD_ARGUMENT_TYPE_MISMATCH("METHOD_ARGUMENT_TYPE_MISMATCH", "요청 값의 타입이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    METHOD_NOT_ALLOWED("METHOD_NOT_ALLOWED", "지원하지 않는 HTTP 메서드입니다.", HttpStatus.METHOD_NOT_ALLOWED),
    API_NOT_FOUND("API_NOT_FOUND", "존재하지 않는 API 경로입니다.", HttpStatus.NOT_FOUND),
    UNAUTHORIZED("UNAUTHORIZED", "인증이 필요합니다.", HttpStatus.UNAUTHORIZED),
    TOKEN_EXPIRED("TOKEN_EXPIRED", "토큰이 만료되었습니다.", HttpStatus.UNAUTHORIZED),
    INVALID_TOKEN("INVALID_TOKEN", "유효하지 않은 토큰입니다.", HttpStatus.UNAUTHORIZED),
    FORBIDDEN("FORBIDDEN", "접근 권한이 없습니다.", HttpStatus.FORBIDDEN),
    INTERNAL_ERROR("INTERNAL_ERROR", "서버 내부 오류가 발생했습니다.", HttpStatus.INTERNAL_SERVER_ERROR),

    // 회원
    MEMBER_NOT_FOUND("MEMBER_NOT_FOUND", "존재하지 않는 회원입니다.", HttpStatus.NOT_FOUND),
    MEMBER_ALREADY_DELETED("MEMBER_ALREADY_DELETED", "이미 탈퇴한 회원입니다.", HttpStatus.CONFLICT),
    MEMBER_NICKNAME_DUPLICATE("MEMBER_NICKNAME_DUPLICATE", "이미 사용 중인 닉네임입니다.", HttpStatus.CONFLICT),
    MEMBER_RESIDENCE_CHANGE_COOLDOWN("MEMBER_RESIDENCE_CHANGE_COOLDOWN", "거주지는 30일마다 1회 변경 가능합니다.", HttpStatus.CONFLICT),

    // 포인트
    POINT_INSUFFICIENT("POINT_INSUFFICIENT", "포인트가 부족합니다.", HttpStatus.CONFLICT),
    POINT_INVALID_AMOUNT("POINT_INVALID_AMOUNT", "포인트 금액은 0보다 커야 합니다.", HttpStatus.BAD_REQUEST),
    POINT_HISTORY_NOT_FOUND("POINT_HISTORY_NOT_FOUND", "포인트 내역을 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    POINT_ORIGINAL_TRANSACTION_NOT_FOUND("POINT_ORIGINAL_TRANSACTION_NOT_FOUND", "환불/정산 대상 원본 거래를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    POINT_INVALID_REFERENCE_TYPE("POINT_INVALID_REFERENCE_TYPE", "유효하지 않은 referenceType입니다.", HttpStatus.BAD_REQUEST),
    POINT_SPEND_FENCED("POINT_SPEND_FENCED", "처리가 중단된 차감 요청입니다.", HttpStatus.CONFLICT),

    // 멱등성
    IDEMPOTENCY_KEY_REQUIRED("IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key 헤더가 필요합니다.", HttpStatus.BAD_REQUEST),
    IDEMPOTENCY_KEY_CONFLICT("IDEMPOTENCY_KEY_CONFLICT", "동일한 키로 다른 내용의 요청이 들어왔습니다.", HttpStatus.CONFLICT),
    // ⚠️ HTTP 200 의도된 동작 — 이미 처리된 요청 재시도 시 정상 응답으로 처리
    POINT_TRANSACTION_ALREADY_PROCESSED("POINT_TRANSACTION_ALREADY_PROCESSED", "이미 처리된 요청입니다.", HttpStatus.OK),

    // 카카오
    KAKAO_AUTH_FAILED("KAKAO_AUTH_FAILED", "카카오 인증에 실패했습니다.", HttpStatus.UNAUTHORIZED),
    KAKAO_SERVER_ERROR("KAKAO_SERVER_ERROR", "카카오 서버에 일시적인 오류가 발생했습니다.", HttpStatus.BAD_GATEWAY);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;

}
