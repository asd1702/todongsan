package com.todongsan.marketservice.global.config;

import com.todongsan.marketservice.market.type.PointRequestKeyStrategy;
import com.todongsan.marketservice.market.type.PointTransportMode;
import com.todongsan.marketservice.market.type.SettlementRequestFormat;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "market.point-transport")
public record PointTransportProperties(
        PointTransportMode mode,
        PointRequestKeyStrategy requestKey,
        SettlementRequestFormat settlementRequestFormat,
        Integer settlementRequestChunkSize
) {

    /** v2 요청 스키마의 items 상한 (contracts/events/market.settlement-payout-requests.v2.schema.json) */
    static final int MAX_CHUNK_SIZE = 1000;

    @ConstructorBinding
    public PointTransportProperties {
        if (mode == null) {
            mode = PointTransportMode.SYNC;
        }
        if (requestKey == null) {
            requestKey = PointRequestKeyStrategy.MEMBER_ID;
        }
        if (settlementRequestFormat == null) {
            settlementRequestFormat = SettlementRequestFormat.ITEM;
        }
        if (settlementRequestChunkSize == null) {
            settlementRequestChunkSize = 500;
        }
        if (settlementRequestChunkSize < 1 || settlementRequestChunkSize > MAX_CHUNK_SIZE) {
            throw new IllegalArgumentException("settlement-request-chunk-size must be 1.." + MAX_CHUNK_SIZE
                    + ": " + settlementRequestChunkSize);
        }
    }

    public PointTransportProperties(PointTransportMode mode, PointRequestKeyStrategy requestKey) {
        this(mode, requestKey, null, null);
    }

    public boolean isAsync() {
        return mode == PointTransportMode.ASYNC;
    }
}
