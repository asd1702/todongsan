package com.todongsan.memberpointservice.point.event;

import org.springframework.kafka.listener.BatchListenerFailedException;

/**
 * 처리 실패 직후 DB 상태 확인(SELECT 1)까지 실패한 경우. 메시지 문제가 아니라 인프라 장애이므로
 * DLT로 보내지 않고 DB가 돌아올 때까지 기다렸다가 다시 처리한다 (ASYNC_POINT_FLOW.md §16.1 Q3).
 *
 * error handler는 같은 offset의 실패 원인이 바뀌었는지를 예외 클래스의 isInstance로 판단한다(resetStateOnExceptionChange).
 * 일반 실패({@link PointRequestRecordFailedException})와 형제 타입으로 둬서, 장애 대기 ↔ 일반 재시도가
 * 바뀔 때마다 재시도 상태가 새로 시작되게 한다 (Market 결과 consumer와 같은 구조).
 */
public class DatabaseUnavailableException extends BatchListenerFailedException {

    public DatabaseUnavailableException(String message, Throwable cause, int index) {
        super(message, cause, index);
    }
}
