package com.todongsan.marketservice.market.client.http;

import com.todongsan.marketservice.market.client.MemberPointClient;
import com.todongsan.marketservice.market.client.MemberPointRefundBatchRequest;
import com.todongsan.marketservice.market.client.MemberPointRefundBatchResponse;
import com.todongsan.marketservice.market.client.MemberPointRefundItemResult;
import com.todongsan.marketservice.market.client.MemberPointRefundItemStatus;
import com.todongsan.marketservice.market.client.MemberPointSettlementBatchRequest;
import com.todongsan.marketservice.market.client.MemberPointSettlementBatchResponse;
import com.todongsan.marketservice.market.client.MemberPointSettlementItemResult;
import com.todongsan.marketservice.market.client.MemberPointSettlementItemStatus;
import com.todongsan.marketservice.market.client.MemberPointTransactionStatus;
import com.todongsan.marketservice.market.client.MemberPointTransactionStatusResponse;
import com.todongsan.marketservice.market.client.PointSpendCommand;
import com.todongsan.marketservice.market.client.exception.MemberPointExternalException;
import com.todongsan.marketservice.market.client.exception.MemberPointTimeoutException;
import com.todongsan.marketservice.market.client.exception.MemberPointUnavailableException;
import com.todongsan.marketservice.market.client.exception.PointInsufficientException;
import com.todongsan.marketservice.market.client.http.MemberPointHttpDtos.RefundBatchResponse;
import com.todongsan.marketservice.market.client.http.MemberPointHttpDtos.RefundItemResult;
import com.todongsan.marketservice.market.client.http.MemberPointHttpDtos.SettlementBatchResponse;
import com.todongsan.marketservice.market.client.http.MemberPointHttpDtos.SettlementItemResult;
import com.todongsan.marketservice.market.client.http.MemberPointHttpDtos.TransactionStatusResponse;
import feign.FeignException;
import feign.Request;
import feign.RetryableException;
import feign.codec.DecodeException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "client.member-point.mode",
        havingValue = "http"
)
public class HttpMemberPointClient implements MemberPointClient {

    private static final String SPEND_REASON = "Market 예측 참여";
    private static final String POINT_INSUFFICIENT = "POINT_INSUFFICIENT";
    private static final String MEMBER_POINT_RESULT_UNKNOWN = "MEMBER_POINT_RESULT_UNKNOWN";
    private static final Pattern ERROR_CODE_PATTERN = Pattern.compile("\"errorCode\"\\s*:\\s*\"([^\"]+)\"");

    private final MemberPointInternalFeignClient feignClient;
    /** 차감만 readTimeout을 짧게 (SPEND_CIRCUIT_BREAKER.md B7). 연결 타임아웃은 다른 호출과 같다 */
    private final Request.Options spendOptions;

    public HttpMemberPointClient(
            MemberPointInternalFeignClient feignClient,
            @Value("${client.member-point.connect-timeout-millis:3000}") long connectTimeoutMillis,
            @Value("${client.member-point.spend-read-timeout-millis:2000}") long spendReadTimeoutMillis
    ) {
        this.feignClient = feignClient;
        this.spendOptions = new Request.Options(
                connectTimeoutMillis, TimeUnit.MILLISECONDS, spendReadTimeoutMillis, TimeUnit.MILLISECONDS, true);
    }

    @Override
    public void spend(PointSpendCommand command) {
        MemberPointApiResponse<MemberPointHttpDtos.SpendResponse> response = call(() -> feignClient.spend(
                spendOptions,
                command.idempotencyKey(),
                command.memberId(),
                MemberPointHttpDtos.SpendRequest.from(command, SPEND_REASON)
        ));
        unwrap(response);
    }

    @Override
    public MemberPointTransactionStatusResponse getTransactionStatus(String idempotencyKey) {
        return toStatusResponse(unwrap(call(() -> feignClient.getTransactionStatus(idempotencyKey))));
    }

    @Override
    public MemberPointTransactionStatusResponse fenceSpend(PointSpendCommand command) {
        return toStatusResponse(unwrap(call(() -> feignClient.fenceSpend(
                command.idempotencyKey(),
                MemberPointHttpDtos.SpendRequest.from(command, SPEND_REASON)
        ))));
    }

    private MemberPointTransactionStatusResponse toStatusResponse(TransactionStatusResponse response) {
        return new MemberPointTransactionStatusResponse(
                response.idempotencyKey(),
                transactionStatus(response.status()),
                response.memberId(),
                response.type(),
                response.amount(),
                response.referenceType(),
                response.referenceId(),
                response.balanceSnapshot(),
                response.createdAt(),
                failureCode(response.failReason(), response.errorCode())
        );
    }

    @Override
    public MemberPointSettlementBatchResponse settleMarketRewards(
            String batchIdempotencyKey,
            MemberPointSettlementBatchRequest request
    ) {
        SettlementBatchResponse response = unwrap(call(() -> feignClient.settleMarketRewards(
                batchIdempotencyKey,
                MemberPointHttpDtos.SettlementBatchRequest.from(request)
        )));
        List<MemberPointSettlementItemResult> results = response.results() == null
                ? null
                : response.results().stream()
                        .map(this::toSettlementResult)
                        .toList();
        return new MemberPointSettlementBatchResponse(response.marketId(), results);
    }

    @Override
    public MemberPointRefundBatchResponse refundMarketPredictions(
            String batchIdempotencyKey,
            MemberPointRefundBatchRequest request
    ) {
        RefundBatchResponse response = unwrap(call(() -> feignClient.refundMarketPredictions(
                batchIdempotencyKey,
                MemberPointHttpDtos.RefundBatchRequest.from(request)
        )));
        List<MemberPointRefundItemResult> results = response.results() == null
                ? null
                : response.results().stream()
                        .map(this::toRefundResult)
                        .toList();
        return new MemberPointRefundBatchResponse(response.marketId(), results);
    }

    private <T> T call(Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (RetryableException e) {
            throw new MemberPointTimeoutException(failureMessage(e, "MEMBER_POINT_TIMEOUT"));
        } catch (DecodeException e) {
            throw new MemberPointExternalException(failureMessage(e, MEMBER_POINT_RESULT_UNKNOWN));
        } catch (FeignException e) {
            throw mapFeignException(e);
        }
    }

    private <T> T unwrap(MemberPointApiResponse<T> response) {
        if (response == null) {
            throw new MemberPointExternalException(MEMBER_POINT_RESULT_UNKNOWN);
        }
        if (!Boolean.TRUE.equals(response.success())) {
            throw mapErrorCode(response.errorCode(), response.message());
        }
        if (response.data() == null) {
            throw new MemberPointExternalException(MEMBER_POINT_RESULT_UNKNOWN);
        }
        return response.data();
    }

    private RuntimeException mapFeignException(FeignException e) {
        String errorCode = extractErrorCode(e.contentUTF8());
        if (POINT_INSUFFICIENT.equals(errorCode)) {
            return new PointInsufficientException(errorCode);
        }
        if (e.status() == 503) {
            return new MemberPointUnavailableException(failureMessage(e, "MEMBER_POINT_UNAVAILABLE"));
        }
        return new MemberPointExternalException(errorCode == null ? failureMessage(e, MEMBER_POINT_RESULT_UNKNOWN) : errorCode);
    }

    private RuntimeException mapErrorCode(String errorCode, String message) {
        if (POINT_INSUFFICIENT.equals(errorCode)) {
            return new PointInsufficientException(errorCode);
        }
        return new MemberPointExternalException(failureMessage(message, errorCode, MEMBER_POINT_RESULT_UNKNOWN));
    }

    private String extractErrorCode(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        Matcher matcher = ERROR_CODE_PATTERN.matcher(body);
        return matcher.find() ? matcher.group(1) : null;
    }

    private MemberPointTransactionStatus transactionStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return MemberPointTransactionStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            return MemberPointTransactionStatus.UNKNOWN;
        }
    }

    private MemberPointSettlementItemResult toSettlementResult(SettlementItemResult result) {
        return new MemberPointSettlementItemResult(
                result.predictionId(),
                result.memberId(),
                settlementStatus(result.status()),
                result.errorCode(),
                result.amount(),
                result.balanceSnapshot()
        );
    }

    private MemberPointSettlementItemStatus settlementStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return MemberPointSettlementItemStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private MemberPointRefundItemResult toRefundResult(RefundItemResult result) {
        return new MemberPointRefundItemResult(
                result.predictionId(),
                result.memberId(),
                refundStatus(result.status()),
                result.errorCode(),
                result.amount(),
                result.balanceSnapshot()
        );
    }

    private MemberPointRefundItemStatus refundStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return MemberPointRefundItemStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String failureCode(String failReason, String errorCode) {
        if (failReason != null && !failReason.isBlank()) {
            return failReason;
        }
        return errorCode;
    }

    private String failureMessage(RuntimeException exception, String fallback) {
        return failureMessage(exception.getMessage(), null, fallback);
    }

    private String failureMessage(String message, String errorCode, String fallback) {
        String value = message == null || message.isBlank() ? errorCode : message;
        if (value == null || value.isBlank()) {
            value = fallback;
        }
        return value.length() <= 255 ? value : value.substring(0, 255);
    }
}
