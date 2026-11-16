package com.lichtluxe.backend.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory sliding-window IP rate limiter for sensitive endpoints (login, checkout).
 *
 * TODO: For multi-instance horizontal scaling, replace this in-memory ConcurrentHashMap
 * with a distributed Redis-backed rate limiter (e.g. Bucket4j with Redis or Redisson).
 */
@Component
public class RateLimitingFilter extends OncePerRequestFilter {

    private static final int MAX_REQUESTS_PER_MINUTE = 15;
    private static final long WINDOW_MS = 60_000L;

    private static class RequestTracker {
        long windowStart;
        AtomicInteger count;

        RequestTracker(long start) {
            this.windowStart = start;
            this.count = new AtomicInteger(1);
        }
    }

    private final Map<String, RequestTracker> rateLimitMap = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getServletPath();
        String method = request.getMethod();

        // Rate limit POST /api/auth/login and POST /api/orders/checkout
        if ("POST".equalsIgnoreCase(method) && 
                (path.startsWith("/api/auth/login") || path.startsWith("/api/orders/checkout"))) {

            String clientIp = getClientIp(request);
            String key = clientIp + ":" + path;
            long now = System.currentTimeMillis();

            RequestTracker tracker = rateLimitMap.compute(key, (k, existing) -> {
                if (existing == null || now - existing.windowStart > WINDOW_MS) {
                    return new RequestTracker(now);
                }
                existing.count.incrementAndGet();
                return existing;
            });

            if (tracker.count.get() > MAX_REQUESTS_PER_MINUTE) {
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setStatus(429); // 429 Too Many Requests

                Map<String, Object> errorBody = Map.of(
                        "status", 429,
                        "error", "Too Many Requests",
                        "message", "Rate limit exceeded. Please wait a moment before trying again.",
                        "path", path
                );
                new ObjectMapper().writeValue(response.getOutputStream(), errorBody);
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}

