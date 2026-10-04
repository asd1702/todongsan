package com.todongsan.marketservice.market.client.http;

import com.todongsan.marketservice.market.client.http.MemberPointHttpDtos.RefundBatchRequest;
import com.todongsan.marketservice.market.client.http.MemberPointHttpDtos.RefundBatchResponse;
import com.todongsan.marketservice.market.client.http.MemberPointHttpDtos.SettlementBatchRequest;
import com.todongsan.marketservice.market.client.http.MemberPointHttpDtos.SettlementBatchResponse;
import com.todongsan.marketservice.market.client.http.MemberPointHttpDtos.SpendRequest;
import com.todongsan.marketservice.market.client.http.MemberPointHttpDtos.SpendResponse;
import com.todongsan.marketservice.market.client.http.MemberPointHttpDtos.TransactionStatusResponse;
import feign.Request;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(
        name = "member-point-internal",
        url = "${client.member-point.base-url:http://localhost:8080}"
)
public interface MemberPointInternalFeignClient {

    /** options: 차감만 readTimeout을 따로 준다 (client.member-point.spend-read-timeout-millis, SPEND_CIRCUIT_BREAKER.md B7) */
    @PostMapping("/internal/api/v1/points/spend")
    MemberPointApiResponse<SpendResponse> spend(
            Request.Options options,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Member-Id") Long memberId,
            @RequestBody SpendRequest request
    );

    @PostMapping("/internal/api/v1/points/spend/fence")
    MemberPointApiResponse<TransactionStatusResponse> fenceSpend(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody SpendRequest request
    );

    @GetMapping("/internal/api/v1/points/transactions")
    MemberPointApiResponse<TransactionStatusResponse> getTransactionStatus(
            @RequestParam("idempotencyKey") String idempotencyKey
    );

    @PostMapping("/internal/api/v1/points/settlements")
    MemberPointApiResponse<SettlementBatchResponse> settleMarketRewards(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody SettlementBatchRequest request
    );

    @PostMapping("/internal/api/v1/points/refunds")
    MemberPointApiResponse<RefundBatchResponse> refundMarketPredictions(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody RefundBatchRequest request
    );
}
