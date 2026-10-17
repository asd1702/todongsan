package com.todongsan.marketservice.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.todongsan.marketservice.global.exception.CustomException;
import com.todongsan.marketservice.global.exception.errorcode.MarketErrorCode;
import com.todongsan.marketservice.market.debug.MarketPredictionDeadlockDebug;
import com.todongsan.marketservice.market.dto.request.QuoteMarketPredictionRequest;
import com.todongsan.marketservice.market.dto.response.QuoteMarketPredictionResponse;
import com.todongsan.marketservice.market.entity.Market;
import com.todongsan.marketservice.market.entity.MarketOption;
import com.todongsan.marketservice.market.repository.MarketMapper;
import com.todongsan.marketservice.market.type.MarketStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MarketPredictionTransactionServiceQuoteTest {

    private static final long MARKET_ID = 1L;
    private static final long SELECTED_OPTION_ID = 2L;

    @Mock
    private MarketMapper marketMapper;

    @Test
    void quotePredictionRejectsNullCurrentPriceAsInvalidOption() {
        MarketPredictionTransactionService service = service();
        MarketOption selectedOption = option(SELECTED_OPTION_ID, "200.00", "0.00", null);
        when(marketMapper.selectMarketById(MARKET_ID)).thenReturn(activeMarket());
        when(marketMapper.selectOptionsByMarketId(MARKET_ID)).thenReturn(List.of(
                option(1L, "800.00", "0.00", "0.80000000"),
                selectedOption
        ));

        assertThatThrownBy(() -> service.quotePrediction(MARKET_ID, quoteRequest()))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(MarketErrorCode.MARKET_INVALID_OPTION);
    }

    @Test
    void quotePredictionRejectsNullRealPoolAmountAsInvalidOption() {
        MarketPredictionTransactionService service = service();
        when(marketMapper.selectMarketById(MARKET_ID)).thenReturn(activeMarket());
        when(marketMapper.selectOptionsByMarketId(MARKET_ID)).thenReturn(List.of(
                option(1L, "800.00", "0.00", "0.80000000"),
                option(SELECTED_OPTION_ID, "200.00", null, "0.20000000")
        ));

        assertThatThrownBy(() -> service.quotePrediction(MARKET_ID, quoteRequest()))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(MarketErrorCode.MARKET_INVALID_OPTION);
    }

    @Test
    void quotePredictionRejectsNullVirtualPoolAmountAsInvalidOption() {
        MarketPredictionTransactionService service = service();
        when(marketMapper.selectMarketById(MARKET_ID)).thenReturn(activeMarket());
        when(marketMapper.selectOptionsByMarketId(MARKET_ID)).thenReturn(List.of(
                option(1L, "800.00", "0.00", "0.80000000"),
                option(SELECTED_OPTION_ID, null, "0.00", "0.20000000")
        ));

        assertThatThrownBy(() -> service.quotePrediction(MARKET_ID, quoteRequest()))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(MarketErrorCode.MARKET_INVALID_OPTION);
    }

    @Test
    void quotePredictionRoundsMoneyScaleWithQuoteRoundingMode() {
        MarketPredictionTransactionService service = service();
        when(marketMapper.selectMarketById(MARKET_ID)).thenReturn(activeMarket());
        when(marketMapper.selectOptionsByMarketId(MARKET_ID)).thenReturn(List.of(
                option(1L, "799.995", "0.00", "0.80000000"),
                option(SELECTED_OPTION_ID, "200.005", "0.00", "0.20000000")
        ));

        QuoteMarketPredictionResponse response = service.quotePrediction(MARKET_ID, quoteRequest());

        assertThat(response.selectedOptionEffectivePoolBefore()).isEqualByComparingTo("200.01");
        assertThat(response.selectedOptionEffectivePoolAfter()).isEqualByComparingTo("300.01");
        assertThat(response.totalEffectivePoolBefore()).isEqualByComparingTo("1000.00");
        assertThat(response.totalEffectivePoolAfter()).isEqualByComparingTo("1100.00");
    }

    private QuoteMarketPredictionRequest quoteRequest() {
        return new QuoteMarketPredictionRequest(SELECTED_OPTION_ID, "100.00");
    }

    private MarketPredictionTransactionService service() {
        return new MarketPredictionTransactionService(
                marketMapper,
                new MarketPredictionDeadlockDebug(false)
        );
    }

    private Market activeMarket() {
        Market market = new Market();
        market.setId(MARKET_ID);
        market.setStatus(MarketStatus.ACTIVE);
        market.setCloseAt(LocalDateTime.now().plusDays(1));
        return market;
    }

    private MarketOption option(
            long optionId,
            String virtualPoolAmount,
            String realPoolAmount,
            String currentPrice
    ) {
        MarketOption option = new MarketOption();
        option.setId(optionId);
        option.setMarketId(MARKET_ID);
        option.setVirtualPoolAmount(toBigDecimal(virtualPoolAmount));
        option.setRealPoolAmount(toBigDecimal(realPoolAmount));
        option.setCurrentPrice(toBigDecimal(currentPrice));
        return option;
    }

    private BigDecimal toBigDecimal(String value) {
        if (value == null) {
            return null;
        }
        return new BigDecimal(value);
    }
}
