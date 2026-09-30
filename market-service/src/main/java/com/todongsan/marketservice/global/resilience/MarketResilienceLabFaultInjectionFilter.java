package com.todongsan.marketservice.global.resilience;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Fault injection restricted to the local resilience-lab profile. */
@Profile("resilience-lab")
@Component
public class MarketResilienceLabFaultInjectionFilter extends OncePerRequestFilter {

    @Value("${resilience.lab.market.delay-ms:0}")
    private long delayMs;

    @Value("${resilience.lab.market.status-code:0}")
    private int statusCode;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.matches("/internal/api/v1/markets/\\d+/basic-info");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ServletException("Resilience lab delay interrupted", e);
            }
        }
        if (statusCode >= 400) {
            response.sendError(statusCode, "Injected by resilience-lab profile");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
