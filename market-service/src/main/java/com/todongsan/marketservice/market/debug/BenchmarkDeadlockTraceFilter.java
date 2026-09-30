package com.todongsan.marketservice.market.debug;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Binds an opt-in benchmark request ID to MDC for phase and exception correlation. */
@Component
@RequiredArgsConstructor
public class BenchmarkDeadlockTraceFilter extends OncePerRequestFilter {

    private final MarketPredictionDeadlockDebug deadlockDebug;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !deadlockDebug.enabled()
                || !"POST".equals(request.getMethod())
                || !request.getRequestURI().matches("/api/v1/markets/\\d+/predictions")
                || request.getHeader(MarketPredictionDeadlockDebug.REQUEST_ID_HEADER) == null;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        MarketPredictionDeadlockDebug.bindRequestId(
                request.getHeader(MarketPredictionDeadlockDebug.REQUEST_ID_HEADER)
        );
        try {
            filterChain.doFilter(request, response);
        } finally {
            deadlockDebug.requestEnd(response.getStatus());
            MarketPredictionDeadlockDebug.clearRequestContext();
        }
    }
}
