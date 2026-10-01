package com.todongsan.marketservice.market.event;

/** 재시도해도 성공할 수 없는 이벤트. 재시도 없이 DLT로 보낸다. */
public class NonRetryableEventException extends RuntimeException {

    public NonRetryableEventException(String message) {
        super(message);
    }

    public NonRetryableEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
