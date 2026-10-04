package com.todongsan.marketservice.market.service;

import com.todongsan.marketservice.global.exception.CustomException;
import com.todongsan.marketservice.global.exception.errorcode.CommonErrorCode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 인기 마켓 예측 참여 입장 제한 (docs/architecture/HOT_MARKET_ADMISSION.md §2).
 *
 * 같은 마켓의 예측은 확정 단계에서 마켓 행 잠금으로 한 줄로 처리되고, 기다리는 동안 DB 커넥션을 쥔다.
 * 마켓마다 동시에 진행 중인 예측 수를 제한하고, 넘치면 기다리지 않고 503 SERVICE_UNAVAILABLE로 즉시 거절한다.
 * 기다리게 하면 톰캣 스레드가 묶여 다른 기능까지 멈춘다.
 */
@Component
public class MarketPredictionAdmission {

    private final int maxInFlightPerMarket;
    private final Map<Long, Semaphore> semaphores = new ConcurrentHashMap<>();

    public MarketPredictionAdmission(
            @Value("${market.prediction.admission.max-in-flight-per-market:0}") int maxInFlightPerMarket
    ) {
        this.maxInFlightPerMarket = maxInFlightPerMarket;
    }

    /** 자리가 없으면 CustomException(SERVICE_UNAVAILABLE, 503). 받은 자리는 반드시 {@link Ticket#close()}로 돌려준다 */
    public Ticket enter(long marketId) {
        if (maxInFlightPerMarket <= 0) {
            return Ticket.NONE;
        }
        Semaphore semaphore = semaphores.computeIfAbsent(marketId, id -> new Semaphore(maxInFlightPerMarket));
        if (!semaphore.tryAcquire()) {
            throw new CustomException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
        return new Ticket(semaphore);
    }

    public static final class Ticket implements AutoCloseable {

        static final Ticket NONE = new Ticket(null);

        private Semaphore semaphore;

        private Ticket(Semaphore semaphore) {
            this.semaphore = semaphore;
        }

        @Override
        public void close() {
            if (semaphore != null) {
                semaphore.release();
                semaphore = null;
            }
        }
    }
}
