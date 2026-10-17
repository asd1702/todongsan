package com.todongsan.marketservice.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.todongsan.marketservice.global.exception.CustomException;
import com.todongsan.marketservice.global.exception.errorcode.MarketErrorCode;
import com.todongsan.marketservice.market.dto.response.CreatePredictionResponse;
import com.todongsan.marketservice.market.service.MarketPredictionTransactionService.ConfirmOutcome;
import com.todongsan.marketservice.market.type.PredictionStatus;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/** 리더-팔로워 묶음 확정 (PREDICTION_BATCH_CONFIRM.md P1·P6·P8) */
class PredictionConfirmBatcherTest {

    private final MarketPredictionTransactionService transactionService = mock(MarketPredictionTransactionService.class);

    @Test
    void 꺼져_있으면_건별_확정을_그대로_부른다() {
        when(transactionService.confirmPrediction(7L)).thenReturn(response(7L));
        PredictionConfirmBatcher batcher = new PredictionConfirmBatcher(transactionService, false, 50, 1000);

        assertThat(batcher.confirm(1L, 7L).getPredictionId()).isEqualTo(7L);
        verify(transactionService, never()).confirmPredictionsInBatch(anyLong(), anyList());
    }

    /** 리더가 묶음 1을 처리하는 동안 들어온 요청들은 다음 묶음 하나로 처리된다 */
    @Test
    void 리더가_처리하는_동안_쌓인_요청은_한_묶음으로_처리된다() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        List<List<Long>> batches = new CopyOnWriteArrayList<>();
        when(transactionService.confirmPredictionsInBatch(eq(1L), anyList())).thenAnswer(invocation -> {
            List<Long> ids = List.copyOf(invocation.getArgument(1));
            batches.add(ids);
            if (batches.size() == 1) {
                release.await(5, TimeUnit.SECONDS);
            }
            return ids.stream().map(id -> ConfirmOutcome.confirmed(response(id))).toList();
        });
        PredictionConfirmBatcher batcher = new PredictionConfirmBatcher(transactionService, true, 50, 5000);
        ExecutorService pool = Executors.newFixedThreadPool(6);

        List<Future<CreatePredictionResponse>> results = new ArrayList<>();
        results.add(pool.submit(() -> batcher.confirm(1L, 100L)));
        waitUntil(() -> batches.size() == 1);
        for (long id = 101; id <= 105; id++) {
            long predictionId = id;
            results.add(pool.submit(() -> batcher.confirm(1L, predictionId)));
        }
        Thread.sleep(200); // 팔로워들이 큐에 들어가고 기다리게 둔다
        release.countDown();

        for (int i = 0; i < results.size(); i++) {
            assertThat(results.get(i).get(5, TimeUnit.SECONDS).getPredictionId()).isEqualTo(100L + i);
        }
        assertThat(batches).hasSize(2);
        assertThat(batches.get(0)).containsExactly(100L);
        assertThat(batches.get(1)).containsExactlyInAnyOrder(101L, 102L, 103L, 104L, 105L);
        pool.shutdown();
    }

    @Test
    void 묶음이_실패하면_건별로_다시_처리하고_실패한_건만_예외를_받는다() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(transactionService.confirmPredictionsInBatch(eq(1L), anyList())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                release.await(5, TimeUnit.SECONDS);
                List<Long> ids = invocation.getArgument(1);
                return ids.stream().map(id -> ConfirmOutcome.confirmed(response(id))).toList();
            }
            throw new IllegalStateException("deadlock victim");
        });
        when(transactionService.confirmPrediction(201L)).thenReturn(response(201L));
        when(transactionService.confirmPrediction(202L)).thenThrow(new CustomException(MarketErrorCode.MARKET_PRICE_UPDATE_CONFLICT));
        PredictionConfirmBatcher batcher = new PredictionConfirmBatcher(transactionService, true, 50, 5000);
        ExecutorService pool = Executors.newFixedThreadPool(3);

        Future<CreatePredictionResponse> leader = pool.submit(() -> batcher.confirm(1L, 200L));
        waitUntil(() -> calls.get() == 1);
        Future<CreatePredictionResponse> ok = pool.submit(() -> batcher.confirm(1L, 201L));
        Future<CreatePredictionResponse> bad = pool.submit(() -> batcher.confirm(1L, 202L));
        Thread.sleep(200);
        release.countDown();

        assertThat(leader.get(5, TimeUnit.SECONDS).getPredictionId()).isEqualTo(200L);
        assertThat(ok.get(5, TimeUnit.SECONDS).getPredictionId()).isEqualTo(201L);
        assertThatThrownBy(() -> bad.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(CustomException.class);
        pool.shutdown();
    }

    /** 리더가 일을 마친 뒤에 들어온 요청은 새 리더가 처리한다 (리더 자리를 놓지 않으면 영영 기다린다) */
    @Test
    void 앞_요청이_끝난_뒤_들어온_요청도_처리된다() {
        stubAllConfirmed();
        PredictionConfirmBatcher batcher = new PredictionConfirmBatcher(transactionService, true, 50, 1000);

        assertThat(batcher.confirm(1L, 300L).getPredictionId()).isEqualTo(300L);
        assertThat(batcher.confirm(1L, 301L).getPredictionId()).isEqualTo(301L);
    }

    /**
     * 리더 교대 경합 (P8): 리더가 큐를 다 비우고 자리를 놓기 직전에 들어온 요청은, 넣은 스레드가 리더가 있다고 보고 기다리기만 한다.
     * 리더가 자리를 놓은 뒤 큐를 다시 확인하지 않으면 그 요청은 주인 없이 남는다. 그 순간을 테스트 지점으로 재현한다.
     */
    @Test
    void 리더가_자리를_놓기_직전에_들어온_요청도_처리된다() throws Exception {
        stubAllConfirmed();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicInteger handoffs = new AtomicInteger();
        List<Future<CreatePredictionResponse>> late = new CopyOnWriteArrayList<>();
        PredictionConfirmBatcher[] holder = new PredictionConfirmBatcher[1];
        holder[0] = new PredictionConfirmBatcher(transactionService, true, 50, 1000) {
            @Override
            void beforeReleasingLeadership() {
                if (handoffs.incrementAndGet() == 1) {
                    Future<CreatePredictionResponse> f = pool.submit(() -> holder[0].confirm(1L, 401L));
                    late.add(f);
                    try {
                        Thread.sleep(200); // 늦은 요청이 큐에 들어가 리더가 있다고 보고 기다리게 둔다
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        };

        assertThat(holder[0].confirm(1L, 400L).getPredictionId()).isEqualTo(400L);
        assertThat(late.get(0).get(2, TimeUnit.SECONDS).getPredictionId()).isEqualTo(401L);
        pool.shutdown();
    }

    private void stubAllConfirmed() {
        when(transactionService.confirmPredictionsInBatch(anyLong(), anyList())).thenAnswer(invocation -> {
            List<Long> ids = invocation.getArgument(1);
            return ids.stream().map(id -> ConfirmOutcome.confirmed(response(id))).toList();
        });
    }

    /**
     * 여러 스레드가 동시에 넣어도 모든 요청이 정확히 한 번 처리되고, 묶음 크기 상한을 지키며, 남는 요청이 없다.
     * 리더 교대 직전에 들어온 요청이 주인 없이 남는 경합(P8)은 이런 부하에서만 드러나므로 여러 번 돌린다.
     */
    @RepeatedTest(20)
    void 동시에_많이_들어와도_모두_한_번씩_처리되고_남는_요청이_없다() throws Exception {
        Map<Long, AtomicInteger> processed = new ConcurrentHashMap<>();
        List<Integer> sizes = Collections.synchronizedList(new ArrayList<>());
        when(transactionService.confirmPredictionsInBatch(anyLong(), anyList())).thenAnswer(invocation -> {
            List<Long> ids = invocation.getArgument(1);
            sizes.add(ids.size());
            ids.forEach(id -> processed.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet());
            Thread.sleep(1);
            return ids.stream().map(id -> ConfirmOutcome.confirmed(response(id))).toList();
        });
        PredictionConfirmBatcher batcher = new PredictionConfirmBatcher(transactionService, true, 8, 5000);
        ExecutorService pool = Executors.newFixedThreadPool(64);

        List<Future<CreatePredictionResponse>> results = new ArrayList<>();
        for (long id = 1; id <= 300; id++) {
            long predictionId = id;
            results.add(pool.submit(() -> batcher.confirm(predictionId % 3, predictionId)));
        }
        for (Future<CreatePredictionResponse> result : results) {
            result.get(5, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(processed).hasSize(300);
        assertThat(processed.values()).allMatch(count -> count.get() == 1);
        assertThat(sizes).allMatch(size -> size >= 1 && size <= 8);
    }

    private static CreatePredictionResponse response(long predictionId) {
        return new CreatePredictionResponse(predictionId, 1L, 1L, new BigDecimal("10.00"),
                new BigDecimal("0.50000000"), new BigDecimal("20.00000000"), PredictionStatus.CONFIRMED);
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 500 && !condition.getAsBoolean(); i++) {
            Thread.sleep(10);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
