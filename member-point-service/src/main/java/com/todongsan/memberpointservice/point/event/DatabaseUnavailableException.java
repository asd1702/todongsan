package com.todongsan.memberpointservice.point.event;

/**
 * 처리 실패 직후 DB 상태 확인(SELECT 1)까지 실패한 경우. 메시지 문제가 아니라 인프라 장애이므로
 * DLT로 보내지 않고 DB가 돌아올 때까지 기다렸다가 다시 처리한다 (ASYNC_POINT_FLOW.md §16.1 Q3).
 *
 * error handler는 같은 offset의 실패 원인이 바뀌었는지를 예외 클래스의 isInstance로 판단한다(resetStateOnExceptionChange).
 * RuntimeException을 상속하면 DB 복구 후 맨 RuntimeException 실패가 "같은 원인"으로 판단되어 장애 대기가 이어지므로,
 * 일반 실패(unchecked)와 상속 관계가 겹치지 않게 checked 예외로 둔다.
 */
public class DatabaseUnavailableException extends Exception {

    public DatabaseUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
