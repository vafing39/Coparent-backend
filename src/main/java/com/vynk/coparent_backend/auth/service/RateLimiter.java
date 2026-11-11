package com.vynk.coparent_backend.auth.service;

import com.vynk.coparent_backend.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory fixed-window rate limiter for the sensitive auth endpoints. Good enough for a single instance;
 * move the counters to a shared store (Redis, database) before running several instances.
 */
@Component
@RequiredArgsConstructor
public class RateLimiter {

    public enum Action {
        LOGIN(10, Duration.ofMinutes(15)),
        SIGNUP(10, Duration.ofHours(1)),
        PASSWORD_FORGOT(5, Duration.ofHours(1)),
        EMAIL_RESEND(5, Duration.ofHours(1));

        private final int limit;
        private final Duration window;

        Action(int limit, Duration window) {
            this.limit = limit;
            this.window = window;
        }
    }

    private record Window(Instant start, int count) {
    }

    private static final int CLEANUP_THRESHOLD = 10_000;

    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    /** Counts one attempt for {@code key}; throws 429 once the limit for the current window is exceeded. */
    public void consume(Action action, String key) {
        Instant now = clock.instant();
        if (windows.size() > CLEANUP_THRESHOLD) {
            windows.values().removeIf(w -> now.isAfter(w.start().plus(Duration.ofHours(1))));
        }
        Window window = windows.compute(action + "|" + key, (k, current) ->
                current == null || !now.isBefore(current.start().plus(action.window))
                        ? new Window(now, 1)
                        : new Window(current.start(), current.count() + 1));
        if (window.count() > action.limit) {
            throw ApiException.tooManyRequests();
        }
    }

    /** Test helper. */
    public void reset() {
        windows.clear();
    }
}
