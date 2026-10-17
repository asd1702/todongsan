package com.todongsan.marketservice.market.service;

import com.todongsan.marketservice.global.exception.CustomException;
import com.todongsan.marketservice.global.exception.errorcode.CommonErrorCode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 예측 참여 입장 제한 (docs/architecture/HOT_MARKET_ADMISSION.md §2, §6).
 *
 * 같은 마켓의 예측은 확정 단계에서 마켓 행 잠금으로 한 줄로 처리되고, 기다리는 동안 DB 커넥션을 쥔다.
 * ① 마켓별 한도: 인기 마켓 하나가 자원을 다 쓰지 못하게 한다 (hot row)
 * ② 전체 한도: 여러 마켓이 동시에 붐벼도 마켓별 한도의 합이 DB 커넥션 풀을 넘지 못하게 한다 (noisy neighbor)
 * 둘 중 하나라도 자리가 없으면 기다리지 않고 503 SERVICE_UNAVAILABLE로 즉시 거절한다.
 * 기다리게 하면 톰캣 스레드가 묶여 다른 기능까지 멈춘다.
 */
@Component
public class MarketPredictionAdmission {

    private final int maxInFlightPerMarket;
    private final Semaphore total;
    private final Map<Long, Semaphore> perMarket = new ConcurrentHashMap<>();

    public MarketPredictionAdmission(
            @Value("${market.prediction.admission.max-in-flight-per-market:0}") int maxInFlightPerMarket,
            @Value("${market.prediction.admission.max-in-flight-total:0}") int maxInFlightTotal
    ) {
        this.maxInFlightPerMarket = maxInFlightPerMarket;
        this.total = maxInFlightTotal > 0 ? new Semaphore(maxInFlightTotal) : null;
    }

    /** 자리가 없으면 CustomException(SERVICE_UNAVAILABLE, 503). 받은 자리는 반드시 {@link Ticket#close()}로 돌려준다 */
    public Ticket enter(long marketId) {
        Semaphore market = maxInFlightPerMarket > 0
                ? perMarket.computeIfAbsent(marketId, id -> new Semaphore(maxInFlightPerMarket))
                : null;
        if (market != null && !market.tryAcquire()) {
            throw new CustomException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
        if (total != null && !total.tryAcquire()) {
            if (market != null) {
                market.release();
            }
            throw new CustomException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
        return new Ticket(market, total);
    }

    public static final class Ticket implements AutoCloseable {

        private Semaphore market;
        private Semaphore total;

        private Ticket(Semaphore market, Semaphore total) {
            this.market = market;
            this.total = total;
        }

        @Override
        public void close() {
            if (market != null) {
                market.release();
                market = null;
            }
            if (total != null) {
                total.release();
                total = null;
            }
        }
    }
}
