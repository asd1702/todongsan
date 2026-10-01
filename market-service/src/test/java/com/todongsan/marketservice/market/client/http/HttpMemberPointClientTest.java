package com.todongsan.marketservice.market.client.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.todongsan.marketservice.market.client.MemberPointRefundBatchRequest;
import com.todongsan.marketservice.market.client.MemberPointRefundItem;
import com.todongsan.marketservice.market.client.MemberPointSettlementBatchRequest;
import com.todongsan.marketservice.market.client.MemberPointSettlementItem;
import com.todongsan.marketservice.market.client.MemberPointTransactionStatus;
import com.todongsan.marketservice.market.client.PointSpendCommand;
import com.todongsan.marketservice.market.client.exception.MemberPointExternalException;
import com.todongsan.marketservice.market.client.exception.PointInsufficientException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class HttpMemberPointClientTest {

    @Test
    void spendSendsIdempotencyKeyMemberHeaderAndReason() {
        CapturingFeignClient feignClient = new CapturingFeignClient();
        feignClient.spendResponse = ok(new MemberPointHttpDtos.SpendResponse(
                10L,
                "SPEND_MARKET",
                new BigDecimal("100.00"),
                "MARKET_PREDICTION",
                1001L,
                new BigDecimal("50.00")
        ));
        HttpMemberPointClient client = new HttpMemberPointClient(feignClient);

        client.spend(new PointSpendCommand(
                10L,
                "SPEND_MARKET",
                new BigDecimal("100.00"),
                "MARKET_PREDICTION",
                1001L,
                "MARKET_PREDICTION_SPEND:market:1:member:10:attempt:1"
        ));

        assertThat(feignClient.idempotencyKey).isEqualTo("MARKET_PREDICTION_SPEND:market:1:member:10:attempt:1");
        assertThat(feignClient.memberIdHeader).isEqualTo(10L);
        assertThat(feignClient.spendRequest.type()).isEqualTo("SPEND_MARKET");
        assertThat(feignClient.spendRequest.referenceType()).isEqualTo("MARKET_PREDICTION");
        assertThat(feignClient.spendRequest.referenceId()).isEqualTo(1001L);
        assertThat(feignClient.spendRequest.reason()).isEqualTo("Market 예측 참여");
    }

    @Test
    void spendMapsPointInsufficientError() {
        CapturingFeignClient feignClient = new CapturingFeignClient();
        feignClient.spendResponse = fail("POINT_INSUFFICIENT", "포인트가 부족합니다.");
        HttpMemberPointClient client = new HttpMemberPointClient(feignClient);

        assertThatThrownBy(() -> client.spend(spendCommand()))
                .isInstanceOf(PointInsufficientException.class)
                .hasMessage("POINT_INSUFFICIENT");
    }

    @Test
    void spendMapsIdempotencyConflictToExternalError() {
        CapturingFeignClient feignClient = new CapturingFeignClient();
        feignClient.spendResponse = fail("IDEMPOTENCY_KEY_CONFLICT", "동일 키 요청 내용이 다릅니다.");
        HttpMemberPointClient client = new HttpMemberPointClient(feignClient);

        assertThatThrownBy(() -> client.spend(spendCommand()))
                .isInstanceOf(MemberPointExternalException.class)
                .hasMessage("동일 키 요청 내용이 다릅니다.");
    }

    @Test
    void transactionStatusUsesFailReasonBeforeErrorCode() {
        CapturingFeignClient feignClient = new CapturingFeignClient();
        feignClient.transactionResponse = ok(new MemberPointHttpDtos.TransactionStatusResponse(
                "key",
                "FAILED",
                10L,
                "SPEND_MARKET",
                new BigDecimal("100.00"),
                "MARKET_PREDICTION",
                1001L,
                new BigDecimal("30.00"),
                null,
                "POINT_INSUFFICIENT",
                "IGNORED_ERROR_CODE"
        ));
        HttpMemberPointClient client = new HttpMemberPointClient(feignClient);

        var response = client.getTransactionStatus("key");

        assertThat(response.status()).isEqualTo(MemberPointTransactionStatus.FAILED);
        assertThat(response.errorCode()).isEqualTo("POINT_INSUFFICIENT");
    }

    /** 펜싱은 원래 spend와 같은 요청 내용을 보내야 MP request_hash가 일치한다 (ASYNC_POINT_FLOW.md §12.6). */
    @Test
    void fenceSpendSendsSameRequestAsSpendAndMapsFencedStatus() {
        CapturingFeignClient feignClient = new CapturingFeignClient();
        feignClient.transactionResponse = ok(new MemberPointHttpDtos.TransactionStatusResponse(
                "key", "FAILED", 10L, "SPEND_MARKET", new BigDecimal("100.00"), "MARKET_PREDICTION", 1001L,
                new BigDecimal("500.00"), null, "POINT_SPEND_FENCED", null
        ));
        HttpMemberPointClient client = new HttpMemberPointClient(feignClient);

        var response = client.fenceSpend(new PointSpendCommand(
                10L, "SPEND_MARKET", new BigDecimal("100.00"), "MARKET_PREDICTION", 1001L, "key"));

        assertThat(feignClient.idempotencyKey).isEqualTo("key");
        assertThat(feignClient.spendRequest.memberId()).isEqualTo(10L);
        assertThat(feignClient.spendRequest.amount()).isEqualByComparingTo("100.00");
        assertThat(feignClient.spendRequest.referenceId()).isEqualTo(1001L);
        assertThat(feignClient.spendRequest.reason()).isEqualTo("Market 예측 참여");
        assertThat(response.status()).isEqualTo(MemberPointTransactionStatus.FAILED);
        assertThat(response.errorCode()).isEqualTo("POINT_SPEND_FENCED");
    }

    @Test
    void settlementSendsHeaderAndBodySettlementIdWithItemResults() {
        CapturingFeignClient feignClient = new CapturingFeignClient();
        feignClient.settlementResponse = ok(new MemberPointHttpDtos.SettlementBatchResponse(
                7L,
                List.of(
                        new MemberPointHttpDtos.SettlementItemResult(
                                1001L,
                                1L,
                                "PROCESSED",
                                null,
                                new BigDecimal("190.00"),
                                new BigDecimal("340.00")
                        ),
                        new MemberPointHttpDtos.SettlementItemResult(
                                1002L,
                                2L,
                                null,
                                "MEMBER_POINT_RESULT_STATUS_MISSING",
                                new BigDecimal("95.00"),
                                null
                        )
                )
        ));
        HttpMemberPointClient client = new HttpMemberPointClient(feignClient);

        var response = client.settleMarketRewards("settlement-key", new MemberPointSettlementBatchRequest(
                7L,
                "settlement-key",
                List.of(new MemberPointSettlementItem(
                        1001L,
                        1L,
                        new BigDecimal("190.00"),
                        "MARKET_PREDICTION",
                        1001L,
                        "Market 정산 보상",
                        "item-key"
                ))
        ));

        assertThat(feignClient.idempotencyKey).isEqualTo("settlement-key");
        assertThat(feignClient.settlementRequest.settlementId()).isEqualTo("settlement-key");
        assertThat(response.results()).hasSize(2);
        assertThat(response.results().get(0).status().name()).isEqualTo("PROCESSED");
        assertThat(response.results().get(1).status()).isNull();
    }

    @Test
    void refundSendsHeaderAndBodyRefundIdWithItemResults() {
        CapturingFeignClient feignClient = new CapturingFeignClient();
        feignClient.refundResponse = ok(new MemberPointHttpDtos.RefundBatchResponse(
                7L,
                List.of(new MemberPointHttpDtos.RefundItemResult(
                        1001L,
                        1L,
                        "ALREADY_PROCESSED",
                        null,
                        new BigDecimal("100.00"),
                        new BigDecimal("200.00")
                ))
        ));
        HttpMemberPointClient client = new HttpMemberPointClient(feignClient);

        var response = client.refundMarketPredictions("refund-key", new MemberPointRefundBatchRequest(
                7L,
                "refund-key",
                List.of(new MemberPointRefundItem(
                        1001L,
                        1L,
                        new BigDecimal("100.00"),
                        "MARKET_PREDICTION",
                        1001L,
                        "Market 무효 처리 환불",
                        "item-key"
                ))
        ));

        assertThat(feignClient.idempotencyKey).isEqualTo("refund-key");
        assertThat(feignClient.refundRequest.refundId()).isEqualTo("refund-key");
        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).status().name()).isEqualTo("ALREADY_PROCESSED");
    }

    private PointSpendCommand spendCommand() {
        return new PointSpendCommand(
                10L,
                "SPEND_MARKET",
                new BigDecimal("100.00"),
                "MARKET_PREDICTION",
                1001L,
                "key"
        );
    }

    private static <T> MemberPointApiResponse<T> ok(T data) {
        return new MemberPointApiResponse<>(true, null, null, data, null);
    }

    private static <T> MemberPointApiResponse<T> fail(String errorCode, String message) {
        return new MemberPointApiResponse<>(false, errorCode, message, null, null);
    }

    static class CapturingFeignClient implements MemberPointInternalFeignClient {
        private String idempotencyKey;
        private Long memberIdHeader;
        private MemberPointHttpDtos.SpendRequest spendRequest;
        private MemberPointHttpDtos.SettlementBatchRequest settlementRequest;
        private MemberPointHttpDtos.RefundBatchRequest refundRequest;
        private MemberPointApiResponse<MemberPointHttpDtos.SpendResponse> spendResponse;
        private MemberPointApiResponse<MemberPointHttpDtos.TransactionStatusResponse> transactionResponse;
        private MemberPointApiResponse<MemberPointHttpDtos.SettlementBatchResponse> settlementResponse;
        private MemberPointApiResponse<MemberPointHttpDtos.RefundBatchResponse> refundResponse;

        @Override
        public MemberPointApiResponse<MemberPointHttpDtos.SpendResponse> spend(
                String idempotencyKey,
                Long memberId,
                MemberPointHttpDtos.SpendRequest request
        ) {
            this.idempotencyKey = idempotencyKey;
            this.memberIdHeader = memberId;
            this.spendRequest = request;
            return spendResponse;
        }

        @Override
        public MemberPointApiResponse<MemberPointHttpDtos.TransactionStatusResponse> getTransactionStatus(
                String idempotencyKey
        ) {
            this.idempotencyKey = idempotencyKey;
            return transactionResponse;
        }

        @Override
        public MemberPointApiResponse<MemberPointHttpDtos.TransactionStatusResponse> fenceSpend(
                String idempotencyKey,
                MemberPointHttpDtos.SpendRequest request
        ) {
            this.idempotencyKey = idempotencyKey;
            this.spendRequest = request;
            return transactionResponse;
        }

        @Override
        public MemberPointApiResponse<MemberPointHttpDtos.SettlementBatchResponse> settleMarketRewards(
                String idempotencyKey,
                MemberPointHttpDtos.SettlementBatchRequest request
        ) {
            this.idempotencyKey = idempotencyKey;
            this.settlementRequest = request;
            return settlementResponse;
        }

        @Override
        public MemberPointApiResponse<MemberPointHttpDtos.RefundBatchResponse> refundMarketPredictions(
                String idempotencyKey,
                MemberPointHttpDtos.RefundBatchRequest request
        ) {
            this.idempotencyKey = idempotencyKey;
            this.refundRequest = request;
            return refundResponse;
        }
    }
}
