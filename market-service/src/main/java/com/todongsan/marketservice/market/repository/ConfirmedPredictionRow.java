package com.todongsan.marketservice.market.repository;

import java.math.BigDecimal;

/** 묶음 확정에서 예측 1건에 쓸 값 (MarketMapper.updatePredictionsConfirmed) */
public record ConfirmedPredictionRow(long predictionId, BigDecimal priceSnapshot, BigDecimal contractQuantity) {
}
