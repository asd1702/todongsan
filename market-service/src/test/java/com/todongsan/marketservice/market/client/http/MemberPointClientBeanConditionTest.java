package com.todongsan.marketservice.market.client.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.todongsan.marketservice.market.client.FakeMemberPointClient;
import com.todongsan.marketservice.market.client.MemberPointClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class MemberPointClientBeanConditionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    void fakeClientIsDefaultMemberPointClient() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(MemberPointClient.class);
            assertThat(context).hasSingleBean(FakeMemberPointClient.class);
            assertThat(context).doesNotHaveBean(HttpMemberPointClient.class);
        });
    }

    @Test
    void httpClientIsActiveOnlyWhenModeIsHttp() {
        contextRunner
                .withPropertyValues("client.member-point.mode=http")
                .run(context -> {
                    assertThat(context).hasSingleBean(MemberPointClient.class);
                    assertThat(context).hasSingleBean(HttpMemberPointClient.class);
                    assertThat(context).doesNotHaveBean(FakeMemberPointClient.class);
                });
    }

    @Configuration
    @Import({FakeMemberPointClient.class, HttpMemberPointClient.class})
    static class TestConfig {

        @Bean
        MemberPointInternalFeignClient memberPointInternalFeignClient() {
            return new NoopMemberPointInternalFeignClient();
        }
    }

    static class NoopMemberPointInternalFeignClient implements MemberPointInternalFeignClient {

        @Override
        public MemberPointApiResponse<MemberPointHttpDtos.SpendResponse> spend(
                String idempotencyKey,
                Long memberId,
                MemberPointHttpDtos.SpendRequest request
        ) {
            return null;
        }

        @Override
        public MemberPointApiResponse<MemberPointHttpDtos.TransactionStatusResponse> getTransactionStatus(
                String idempotencyKey
        ) {
            return null;
        }

        @Override
        public MemberPointApiResponse<MemberPointHttpDtos.TransactionStatusResponse> fenceSpend(
                String idempotencyKey,
                MemberPointHttpDtos.SpendRequest request
        ) {
            return null;
        }

        @Override
        public MemberPointApiResponse<MemberPointHttpDtos.SettlementBatchResponse> settleMarketRewards(
                String idempotencyKey,
                MemberPointHttpDtos.SettlementBatchRequest request
        ) {
            return null;
        }

        @Override
        public MemberPointApiResponse<MemberPointHttpDtos.RefundBatchResponse> refundMarketPredictions(
                String idempotencyKey,
                MemberPointHttpDtos.RefundBatchRequest request
        ) {
            return null;
        }
    }
}
