package com.todongsan.marketservice.market.client.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;

class MemberPointInternalFeignClientTest {

    @Test
    void feignClientUsesConfiguredBaseUrl() {
        FeignClient annotation = MemberPointInternalFeignClient.class.getAnnotation(FeignClient.class);

        assertThat(annotation.name()).isEqualTo("member-point-internal");
        assertThat(annotation.url()).isEqualTo("${client.member-point.base-url:http://localhost:8080}");
    }

    @Test
    void feignClientUsesInternalPointEndpoints() throws NoSuchMethodException {
        assertThat(postMapping("spend", feign.Request.Options.class, String.class, Long.class, MemberPointHttpDtos.SpendRequest.class))
                .containsExactly("/internal/api/v1/points/spend");
        assertThat(getMapping("getTransactionStatus", String.class))
                .containsExactly("/internal/api/v1/points/transactions");
        assertThat(postMapping(
                "settleMarketRewards",
                String.class,
                MemberPointHttpDtos.SettlementBatchRequest.class
        )).containsExactly("/internal/api/v1/points/settlements");
        assertThat(postMapping(
                "refundMarketPredictions",
                String.class,
                MemberPointHttpDtos.RefundBatchRequest.class
        )).containsExactly("/internal/api/v1/points/refunds");
    }

    @Test
    void spendRequestIncludesRequiredHeaders() throws NoSuchMethodException {
        Method method = MemberPointInternalFeignClient.class.getMethod(
                "spend",
                feign.Request.Options.class,
                String.class,
                Long.class,
                MemberPointHttpDtos.SpendRequest.class
        );

        assertThat(requestHeaderValue(method.getParameterAnnotations()[1])).isEqualTo("Idempotency-Key");
        assertThat(requestHeaderValue(method.getParameterAnnotations()[2])).isEqualTo("X-Member-Id");
    }

    private String[] postMapping(String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
        return MemberPointInternalFeignClient.class.getMethod(methodName, parameterTypes)
                .getAnnotation(PostMapping.class)
                .value();
    }

    private String[] getMapping(String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
        return MemberPointInternalFeignClient.class.getMethod(methodName, parameterTypes)
                .getAnnotation(GetMapping.class)
                .value();
    }

    private String requestHeaderValue(Annotation[] annotations) {
        for (Annotation annotation : annotations) {
            if (annotation instanceof RequestHeader requestHeader) {
                return requestHeader.value();
            }
        }
        return null;
    }
}
