package com.todongsan.memberpointservice.point.event;

import org.springframework.kafka.listener.BatchListenerFailedException;

/** 배치 중 index 위치 건의 일반 실패. 기존 정책(NonRetryable 즉시, 그 외 재시도 후 DLT)으로 처리된다. */
public class PointRequestRecordFailedException extends BatchListenerFailedException {

    public PointRequestRecordFailedException(String message, Throwable cause, int index) {
        super(message, cause, index);
    }
}
