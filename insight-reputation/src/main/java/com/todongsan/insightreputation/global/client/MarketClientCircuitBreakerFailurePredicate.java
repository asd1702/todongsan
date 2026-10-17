package com.todongsan.insightreputation.global.client;

import com.todongsan.insightreputation.global.exception.CustomException;
import com.todongsan.insightreputation.global.exception.errorcode.ErrorCode;

import java.util.function.Predicate;

/**
 * Records Market availability failures while excluding known business outcomes.
 * Unknown exceptions are recorded because the downstream interaction did not
 * complete in a known, business-valid way.
 */
public class MarketClientCircuitBreakerFailurePredicate implements Predicate<Throwable> {

    @Override
    public boolean test(Throwable throwable) {
        if (!(throwable instanceof CustomException customException)) {
            return true;
        }

        return switch (customException.getErrorCode()) {
            case EXTERNAL_SERVICE_TIMEOUT,
                 EXTERNAL_SERVICE_UNAVAILABLE,
                 EXTERNAL_SERVICE_ERROR,
                 EXTERNAL_SERVICE_BAD_RESPONSE -> true;
            default -> false;
        };
    }
}
