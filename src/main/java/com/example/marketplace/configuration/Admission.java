package com.example.marketplace.configuration;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-instance overload protection. Edge/global fairness and bot controls remain separate.
 */
@Component
public class Admission extends OncePerRequestFilter {
    private final Semaphore checkout = new Semaphore(8, true);
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final MeterRegistry metrics;

    public Admission(MeterRegistry metrics) {
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > 65536) {
            response.sendError(413);
            return;
        }
        String path = request.getRequestURI();
        if (!path.startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }
        long minute = System.currentTimeMillis() / 60000;
        if (windows.size() > 10000) windows.entrySet().removeIf(e -> e.getValue().minute() < minute);
        String identity =
                request.getUserPrincipal() == null
                        ? request.getRemoteAddr()
                        : request.getUserPrincipal().getName();
        if (windows.size() >= 10000 && !windows.containsKey(identity)) {
            reject(response);
            return;
        }
        Window window =
                windows.compute(
                        identity,
                        (key, old) ->
                                old == null || old.minute() != minute
                                        ? new Window(minute, new AtomicInteger())
                                        : old);
        if (window.count().incrementAndGet() > 600) {
            reject(response);
            return;
        }
        boolean limited = path.equals("/api/v1/checkouts");
        if (limited && !checkout.tryAcquire()) {
            reject(response);
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            if (limited) checkout.release();
        }
    }

    private void reject(HttpServletResponse r) throws IOException {
        metrics.counter("marketplace.admission.rejected").increment();
        r.setStatus(429);
        r.setHeader("Retry-After", "5");
        r.setContentType("application/problem+json");
        r.getWriter()
                .write(
                        "{\"title\":\"Capacity limit\",\"status\":429,\"detail\":\"Retry with the same"
                                + " idempotency key\"}");
    }

    private record Window(long minute, AtomicInteger count) {
    }
}
