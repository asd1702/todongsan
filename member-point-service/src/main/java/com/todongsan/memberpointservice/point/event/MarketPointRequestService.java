package com.todongsan.memberpointservice.point.event;

import com.todongsan.memberpointservice.point.dto.response.BatchItemResult;
import com.todongsan.memberpointservice.point.entity.PointHistoryType;
import com.todongsan.memberpointservice.point.service.PointInternalService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 적립 처리와 결과 outbox 기록을 한 트랜잭션으로 묶는다. */
@Service
@RequiredArgsConstructor
public class MarketPointRequestService {

    private final PointInternalService pointInternalService;
    private final PointResultOutboxWriter pointResultOutboxWriter;

    @Transactional
    public BatchItemResult process(MarketPointRequestEvent event, PointHistoryType type) {
        MarketPointRequestEvent.Data data = event.data();
        BatchItemResult result = pointInternalService.creditMarketPoint(
                type,
                data.predictionId(),
                data.memberId(),
                data.amount(),
                data.referenceType(),
                data.referenceId(),
                data.reason(),
                data.idempotencyKey()
        );
        pointResultOutboxWriter.append(event, result);
        return result;
    }
}
