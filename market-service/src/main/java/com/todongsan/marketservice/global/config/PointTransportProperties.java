package com.todongsan.marketservice.global.config;

import com.todongsan.marketservice.market.type.PointRequestKeyStrategy;
import com.todongsan.marketservice.market.type.PointTransportMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "market.point-transport")
public record PointTransportProperties(
        PointTransportMode mode,
        PointRequestKeyStrategy requestKey
) {

    public PointTransportProperties {
        if (mode == null) {
            mode = PointTransportMode.SYNC;
        }
        if (requestKey == null) {
            requestKey = PointRequestKeyStrategy.MEMBER_ID;
        }
    }

    public boolean isAsync() {
        return mode == PointTransportMode.ASYNC;
    }
}
