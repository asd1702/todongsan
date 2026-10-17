package com.todongsan.marketservice.market.service;

import com.todongsan.marketservice.global.exception.CustomException;
import com.todongsan.marketservice.global.exception.errorcode.MarketErrorCode;
import com.todongsan.marketservice.market.dto.response.CreatePredictionResponse;
import com.todongsan.marketservice.market.service.MarketPredictionTransactionService.ConfirmOutcome;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 예측 확정 묶음 처리 — 리더-팔로워 (docs/architecture/PREDICTION_BATCH_CONFIRM.md P1~P10).
 *
 * 요청 스레드는 확정 요청을 마켓 큐에 넣는다. 그 마켓을 처리 중인 리더가 없으면 자신이 리더가 되어,
 * 큐가 빌 때까지 최대 maxBatchSize건씩 꺼내 트랜잭션 1개로 확정한다. 나머지는 결과를 기다린다(커넥션을 쥐지 않는다).
 * 한가할 때는 묶음이 1건이라 지연이 늘지 않고, 붐빌수록 앞 묶음이 commit하는 동안 쌓인 만큼 커진다.
 * 같은 마켓은 인스턴스 안에서 한 번에 한 묶음만 처리되고, 인스턴스 사이 순서는 DB 잠금이 정한다.
 */
@Slf4j
@Component
public class PredictionConfirmBatcher {

    private final MarketPredictionTransactionService transactionService;
    private final boolean enabled;
    private final int maxBatchSize;
    private final long waitTimeoutMillis;
    private final Map<Long, MarketQueue> queues = new ConcurrentHashMap<>();

    public PredictionConfirmBatcher(
            MarketPredictionTransactionService transactionService,
            @Value("${market.prediction.batch-confirm.enabled:false}") boolean enabled,
            @Value("${market.prediction.batch-confirm.max-batch-size:50}") int maxBatchSize,
            @Value("${market.prediction.batch-confirm.wait-timeout-ms:10000}") long waitTimeoutMillis
    ) {
        this.transactionService = transactionService;
        this.enabled = enabled;
        this.maxBatchSize = maxBatchSize;
        this.waitTimeoutMillis = waitTimeoutMillis;
    }

    /** 결과는 건별 확정(confirmPrediction)과 같다: 응답 또는 같은 예외 */
    public CreatePredictionResponse confirm(long marketId, long predictionId) {
        if (!enabled) {
            return transactionService.confirmPrediction(predictionId);
        }
        Request request = new Request(predictionId);
        MarketQueue queue = queues.computeIfAbsent(marketId, id -> new MarketQueue());
        queue.requests.add(request);
        drain(marketId, queue);
        return await(request);
    }

    /**
     * 리더가 될 수 있으면 큐가 빌 때까지 처리한다 (P8).
     * 자리를 놓은 뒤 큐를 다시 확인한다: 놓기 직전에 들어온 요청을 넣은 스레드는 리더가 있다고 보고 기다리기만 하므로,
     * 여기서 다시 확인하지 않으면 그 요청은 주인 없이 남는다.
     */
    private void drain(long marketId, MarketQueue queue) {
        while (!queue.requests.isEmpty() && queue.leader.compareAndSet(false, true)) {
            try {
                List<Request> batch;
                while (!(batch = poll(queue)).isEmpty()) {
                    process(marketId, batch);
                }
            } finally {
                beforeReleasingLeadership();
                queue.leader.set(false);
            }
        }
    }

    /** 리더 자리를 놓기 직전 (교대 경합 테스트용 지점, 운영에서는 아무것도 하지 않는다) */
    void beforeReleasingLeadership() {
    }

    private List<Request> poll(MarketQueue queue) {
        List<Request> batch = new ArrayList<>();
        Request request;
        while (batch.size() < maxBatchSize && (request = queue.requests.poll()) != null) {
            batch.add(request);
        }
        return batch;
    }

    /** 묶음 트랜잭션이 실패하면 그 묶음을 건별 경로로 하나씩 처리한다 (P6). 어떤 경우에도 모든 요청을 완료한다 */
    private void process(long marketId, List<Request> batch) {
        try {
            List<ConfirmOutcome> outcomes = transactionService.confirmPredictionsInBatch(
                    marketId, batch.stream().map(Request::predictionId).toList());
            for (int i = 0; i < batch.size(); i++) {
                ConfirmOutcome outcome = outcomes.get(i);
                if (outcome.failure() == null) {
                    batch.get(i).result.complete(outcome.response());
                } else {
                    batch.get(i).result.completeExceptionally(outcome.failure());
                }
            }
            if (batch.size() > 1) {
                log.debug("Prediction batch confirmed. marketId={}, size={}", marketId, batch.size());
            }
        } catch (RuntimeException batchFailure) {
            log.warn("Prediction batch confirm failed, falling back to per-item. marketId={}, size={}, cause={}",
                    marketId, batch.size(), batchFailure.toString());
            for (Request request : batch) {
                try {
                    request.result.complete(transactionService.confirmPrediction(request.predictionId()));
                } catch (RuntimeException e) {
                    request.result.completeExceptionally(e);
                }
            }
        }
    }

    private CreatePredictionResponse await(Request request) {
        try {
            return request.result.get(waitTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e.getCause());
        } catch (TimeoutException e) {
            // 리더가 나중에 확정할 수 있다. 확정하지 못하면 POINT_PENDING으로 남아 대사가 정리한다
            log.warn("Prediction confirm wait timed out. predictionId={}", request.predictionId());
            throw new CustomException(MarketErrorCode.MARKET_PRICE_UPDATE_CONFLICT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CustomException(MarketErrorCode.MARKET_PRICE_UPDATE_CONFLICT);
        }
    }

    private static final class MarketQueue {
        private final Queue<Request> requests = new ConcurrentLinkedQueue<>();
        private final AtomicBoolean leader = new AtomicBoolean(false);
    }

    private record Request(long predictionId, CompletableFuture<CreatePredictionResponse> result) {
        Request(long predictionId) {
            this(predictionId, new CompletableFuture<>());
        }
    }
}
