package com.todongsan.marketservice.market.controller;

import com.todongsan.marketservice.global.response.ApiResponse;
import com.todongsan.marketservice.market.debug.MarketPredictionDeadlockDebug;
import com.todongsan.marketservice.market.dto.request.CreatePredictionRequest;
import com.todongsan.marketservice.market.dto.request.QuoteMarketPredictionRequest;
import com.todongsan.marketservice.market.dto.response.CreatePredictionResponse;
import com.todongsan.marketservice.market.dto.response.MarketDetailResponse;
import com.todongsan.marketservice.market.dto.response.MarketListResponse;
import com.todongsan.marketservice.market.dto.response.MarketPredictionResponse;
import com.todongsan.marketservice.market.dto.response.MarketPriceHistoryResponse;
import com.todongsan.marketservice.market.dto.response.MyMarketPredictionPageResponse;
import com.todongsan.marketservice.market.dto.response.QuoteMarketPredictionResponse;
import com.todongsan.marketservice.market.service.MarketPredictionQueryService;
import com.todongsan.marketservice.market.service.MarketPredictionService;
import com.todongsan.marketservice.market.service.MarketService;
import com.todongsan.marketservice.market.type.MarketDisplayStatus;
import com.todongsan.marketservice.market.type.MarketStatus;
import com.todongsan.marketservice.market.type.PredictionStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/markets")
@Tag(name = "Market Public API", description = "Gateway를 통해 노출되는 Market 공개 조회 API")
@io.swagger.v3.oas.annotations.responses.ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_FAILED"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "MARKET_NOT_FOUND / MARKET_OPTION_NOT_FOUND / MARKET_PREDICTION_NOT_FOUND"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "MARKET_NOT_ACTIVE / MARKET_ALREADY_PREDICTED / MARKET_CLOSED / MARKET_PRICE_UPDATE_CONFLICT")
})
public class MarketController {

    private final MarketService marketService;
    private final MarketPredictionQueryService marketPredictionQueryService;
    private final MarketPredictionService marketPredictionService;
    private final MarketPredictionDeadlockDebug deadlockDebug;

    @GetMapping
    @Operation(
            summary = "Market 목록 조회",
            description = "Market 목록을 상태, 표시 상태, 키워드, 정렬, 페이지 조건으로 조회한다. Decimal 응답은 JSON String으로 제공한다."
    )
    public ApiResponse<MarketListResponse> getMarkets(
            @Parameter(description = "페이지 번호. 0부터 시작", example = "0")
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "페이지 크기. 최대 100", example = "20")
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @Parameter(description = "Market 상태 필터", example = "ACTIVE")
            @RequestParam(required = false) MarketStatus status,
            @Parameter(description = "제목 검색 키워드", example = "아파트")
            @RequestParam(required = false) String keyword,
            @Parameter(description = "프론트 표시 상태 필터", example = "ACTIVE")
            @RequestParam(required = false) MarketDisplayStatus displayStatus,
            @Parameter(description = "정렬 방식: popular, closingSoon, latest", example = "popular")
            @RequestParam(required = false) String sort
    ) {
        return ApiResponse.ok(marketService.getMarkets(page, size, status, keyword, displayStatus, sort));
    }

    @GetMapping("/{marketId}")
    @Operation(summary = "Market 상세 조회", description = "Market 기본 정보와 선택지의 현재 가격을 조회한다.")
    public ApiResponse<MarketDetailResponse> getMarket(
            @Parameter(description = "Market ID", example = "1")
            @PathVariable @Min(1) long marketId
    ) {
        return ApiResponse.ok(marketService.getMarket(marketId));
    }

    @GetMapping("/{marketId}/price-history")
    @Tag(name = "Market PriceHistory API", description = "Market 가격 그래프용 PriceHistory 조회 API")
    @Operation(
            summary = "Market PriceHistory 조회",
            description = "그래프 표시용 가격 이력을 조회한다. PriceHistory는 정산 계산 원천이 아니며, Decimal 응답은 JSON String으로 제공한다."
    )
    public ApiResponse<MarketPriceHistoryResponse> getPriceHistory(
            @Parameter(description = "Market ID", example = "1")
            @PathVariable @Min(1) long marketId,
            @Parameter(description = "페이지 번호. 0부터 시작", example = "0")
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "페이지 크기. 최대 100", example = "50")
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size,
            @Parameter(description = "선택지 ID 필터", example = "11")
            @RequestParam(required = false) @Min(1) Long optionId
    ) {
        return ApiResponse.ok(marketService.getPriceHistory(marketId, page, size, optionId));
    }

    @PostMapping("/{marketId}/predictions")
    @Tag(name = "Market Prediction API", description = "Gateway를 통해 노출되는 예측 참여 API")
    @Operation(
            summary = "Market 예측 참여",
            description = "실제 예측 참여를 생성한다. Quote가 아니라 참여 시점의 최신 가격으로 priceSnapshot과 contractQuantity가 확정된다."
    )
    public ApiResponse<CreatePredictionResponse> createPrediction(
            @Parameter(description = "Market ID", example = "1")
            @PathVariable @Min(1) long marketId,
            @Parameter(
                    name = "X-Member-Id",
                    description = "Gateway가 주입하는 회원 ID. Market Service는 JWT를 직접 파싱하지 않는다.",
                    required = true,
                    in = ParameterIn.HEADER,
                    example = "10"
            )
            @RequestHeader(value = "X-Member-Id", required = false) Long memberId,
            @Parameter(
                    name = "Idempotency-Key",
                    description = "예측 참여 포인트 차감 멱등성 키. 예: MARKET_PREDICTION_SPEND:market:1:member:10",
                    required = true,
                    in = ParameterIn.HEADER,
                    example = "MARKET_PREDICTION_SPEND:market:1:member:10"
            )
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreatePredictionRequest request
    ) {
        deadlockDebug.requestStart(marketId, memberId);
        CreatePredictionResponse response = marketPredictionService.createPrediction(
                marketId,
                memberId,
                idempotencyKey,
                request
        );
        if (response.getStatus() == PredictionStatus.POINT_UNKNOWN) {
            return ApiResponse.ok(response, "예측 참여 처리 상태를 확인 중입니다.");
        }
        return ApiResponse.ok(response);
    }

    @PostMapping("/{marketId}/predictions/quote")
    @Tag(name = "Market Prediction API", description = "Gateway를 통해 노출되는 예측 참여 API")
    @Operation(
            summary = "Market 예측 참여 Quote",
            description = """
                    Quote는 확정 견적이 아니라 미리보기이다.
                    실제 예측 참여 시점의 가격 기준으로 priceSnapshot과 contractQuantity가 확정된다.
                    Quote는 Prediction 생성, Member-Point 호출, pool 변경, PriceHistory 저장을 하지 않는다.
                    """
    )
    public ApiResponse<QuoteMarketPredictionResponse> quotePrediction(
            @Parameter(description = "Market ID", example = "1")
            @PathVariable @Min(1) long marketId,
            @Valid @RequestBody QuoteMarketPredictionRequest request
    ) {
        return ApiResponse.ok(marketPredictionService.quotePrediction(marketId, request));
    }

    @GetMapping("/predictions/me")
    @Tag(name = "Market Prediction API", description = "Gateway를 통해 노출되는 예측 참여 API")
    @Operation(
            summary = "내 예측 목록 조회",
            description = "Gateway가 주입한 X-Member-Id 기준으로 내 예측 목록을 최신 참여 시점 순으로 조회한다."
    )
    public ApiResponse<MyMarketPredictionPageResponse> getMyPredictions(
            @Parameter(
                    name = "X-Member-Id",
                    description = "Gateway가 주입하는 회원 ID. Market Service는 JWT를 직접 파싱하지 않는다.",
                    required = true,
                    in = ParameterIn.HEADER,
                    example = "10"
            )
            @RequestHeader(value = "X-Member-Id", required = false) Long memberId,
            @Parameter(description = "페이지 번호. 0부터 시작", example = "0")
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "페이지 크기. 최대 100", example = "20")
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @Parameter(description = "Market display status filter. Repeated or comma-separated values are supported.",
                    example = "ACTIVE,CLOSED_BY_TIME")
            @RequestParam(required = false) List<String> marketDisplayStatus,
            @Parameter(description = "Prediction status filter. Repeated or comma-separated values are supported.",
                    example = "POINT_PENDING,POINT_UNKNOWN")
            @RequestParam(required = false) List<String> predictionStatus
    ) {
        return ApiResponse.ok(marketPredictionQueryService.getMyPredictions(
                memberId,
                page,
                size,
                marketDisplayStatus,
                predictionStatus
        ));
    }

    @GetMapping("/{marketId}/predictions/me")
    @Tag(name = "Market Prediction API", description = "Gateway를 통해 노출되는 예측 참여 API")
    @Operation(summary = "내 예측 참여 조회", description = "Gateway가 주입한 X-Member-Id 기준으로 내 예측 참여를 조회한다.")
    public ApiResponse<MarketPredictionResponse> getMyPrediction(
            @Parameter(description = "Market ID", example = "1")
            @PathVariable @Min(1) long marketId,
            @Parameter(
                    name = "X-Member-Id",
                    description = "Gateway가 주입하는 회원 ID. Market Service는 JWT를 직접 파싱하지 않는다.",
                    required = true,
                    in = ParameterIn.HEADER,
                    example = "10"
            )
            @RequestHeader(value = "X-Member-Id", required = false) Long memberId
    ) {
        return ApiResponse.ok(marketPredictionQueryService.getMyPrediction(marketId, memberId));
    }
}
