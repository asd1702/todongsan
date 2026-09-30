package com.todongsan.marketservice.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.todongsan.marketservice.market.client.MemberPointRefundBatchResponse;
import com.todongsan.marketservice.market.client.MemberPointRefundItemResult;
import com.todongsan.marketservice.market.client.MemberPointRefundItemStatus;
import com.todongsan.marketservice.market.dto.response.RefundMarketResponse;
import com.todongsan.marketservice.market.entity.MarketRefundDetail;
import com.todongsan.marketservice.market.outbox.PointRequestOutboxWriter;
import com.todongsan.marketservice.market.repository.MarketMapper;
import com.todongsan.marketservice.market.type.RefundStatus;
import com.todongsan.marketservice.market.type.TransactionItemStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MarketRefundTransactionServiceTest {

    @Mock
    private MarketMapper marketMapper;

    @Mock
    private PointRequestOutboxWriter pointRequestOutboxWriter;

    @Test
    void applyRefundRetryResultSkipsItemWhenDetailUpdateCountIsZero() {
        MarketRefundTransactionService service = new MarketRefundTransactionService(marketMapper, pointRequestOutboxWriter);
        MarketRefundDetail detail = refundDetail();
        MarketRefundRetryPreparation preparation = new MarketRefundRetryPreparation(
                100L,
                500L,
                List.of(detail),
                false
        );
        MemberPointRefundBatchResponse response = new MemberPointRefundBatchResponse(
                100L,
                List.of(new MemberPointRefundItemResult(
                        1001L,
                        1L,
                        MemberPointRefundItemStatus.PROCESSED,
                        null,
                        new BigDecimal("100.00"),
                        null
                ))
        );
        when(marketMapper.updateRetryRefundDetailStatus(
                org.mockito.ArgumentMatchers.eq(9001L),
                org.mockito.ArgumentMatchers.eq(TransactionItemStatus.SUCCESS),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class)
        )).thenReturn(0);
        when(marketMapper.countNonSuccessRefundDetails(500L)).thenReturn(1L);

        RefundMarketResponse result = service.applyRefundRetryResult(preparation, response);

        assertThat(result.successCount()).isZero();
        assertThat(result.failedCount()).isZero();
        assertThat(result.unknownCount()).isZero();
        assertThat(result.refundStatus()).isEqualTo(RefundStatus.IN_PROGRESS);
        verify(marketMapper, never()).markPredictionRefunded(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(BigDecimal.class),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class)
        );
    }

    private MarketRefundDetail refundDetail() {
        MarketRefundDetail detail = new MarketRefundDetail();
        detail.setId(9001L);
        detail.setMarketVoidId(500L);
        detail.setPredictionId(1001L);
        detail.setMemberId(1L);
        detail.setRefundAmount(new BigDecimal("100.00"));
        detail.setStatus(TransactionItemStatus.FAILED);
        detail.setIdempotencyKey("EXISTING_REFUND_KEY_1001");
        detail.setCreatedAt(LocalDateTime.now().minusMinutes(10));
        detail.setUpdatedAt(LocalDateTime.now().minusMinutes(10));
        return detail;
    }
}
