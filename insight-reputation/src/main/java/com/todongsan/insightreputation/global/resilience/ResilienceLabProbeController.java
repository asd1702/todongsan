package com.todongsan.insightreputation.global.resilience;

import com.todongsan.insightreputation.global.client.ActiveMarketInfoResponse;
import com.todongsan.insightreputation.global.client.MarketClient;
import com.todongsan.insightreputation.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Local-only probe for repeatable resilience experiments. Never loaded in production profiles. */
@Profile("resilience-lab")
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/lab/resilience")
public class ResilienceLabProbeController {

    private final MarketClient marketClient;

    @GetMapping("/market/{marketId}")
    public ApiResponse<ActiveMarketInfoResponse> marketBasicInfo(@PathVariable long marketId) {
        return ApiResponse.success(marketClient.getActiveMarketInfo(marketId));
    }
}
