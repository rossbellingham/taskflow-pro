package com.taskflow.security;

import com.taskflow.exception.TooManyRequestsException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slows down password guessing by counting FAILED attempts in a fixed 15-minute window:
 *
 *   - 5 failures for the same username      -> that username is locked until the window ends
 *   - 20 failures from the same IP address  -> that address is locked until the window ends
 *   - 5 wrong "current password" entries on the change-password endpoint -> locked likewise
 *
 * While locked, even the CORRECT password is refused (HTTP 429), which is what stops guessing.
 * A successful login clears that username's counter.
 *
 * Counters live in memory: they reset when the app restarts and are not shared between servers. That is
 * fine for one instance; several instances would need a shared store such as Redis.
 *
 * Trade-off: because usernames are locked, someone can deliberately lock out a victim's username by
 * failing logins against it. The lock expires on its own, so the damage is limited to a 15-minute nuisance.
 *
 * The IP is request.getRemoteAddr(). Behind a reverse proxy that would be the proxy's address; you would
 * then enable Spring's forwarded-header support so the real client address is used.
 */
@Service
public class LoginAttemptService {

    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final Limiter perUser = new Limiter(5, WINDOW);
    private final Limiter perIp = new Limiter(20, WINDOW);
    private final Limiter passwordChange = new Limiter(5, WINDOW);

    // ---- login ----

    public void checkLoginAllowed(String username, String ip) {
        perUser.check(username);
        perIp.check(ip);
    }

    public void recordLoginFailure(String username, String ip) {
        perUser.failure(username);
        perIp.failure(ip);
    }

    public void recordLoginSuccess(String username) {
        perUser.clear(username);
    }

    // ---- change password ----

    public void checkPasswordChangeAllowed(String username) {
        passwordChange.check(username);
    }

    public void recordPasswordChangeFailure(String username) {
        passwordChange.failure(username);
    }

    public void recordPasswordChangeSuccess(String username) {
        passwordChange.clear(username);
    }

    /** Failure counter per key within a fixed window that starts at the first failure. */
    private static final class Limiter {

        private record Entry(int failures, Instant windowStart) {}

        private static final int PURGE_THRESHOLD = 10_000;

        private final int maxFailures;
        private final Duration window;
        private final Map<String, Entry> entries = new ConcurrentHashMap<>();

        Limiter(int maxFailures, Duration window) {
            this.maxFailures = maxFailures;
            this.window = window;
        }

        void check(String key) {
            Entry entry = entries.get(key);
            if (entry == null) {
                return;
            }
            Instant now = Instant.now();
            Instant windowEnd = entry.windowStart().plus(window);
            if (entry.failures() >= maxFailures && now.isBefore(windowEnd)) {
                long wait = Math.max(1, Duration.between(now, windowEnd).toSeconds());
                throw new TooManyRequestsException(wait);
            }
        }

        void failure(String key) {
            Instant now = Instant.now();
            entries.compute(key, (k, entry) -> {
                if (entry == null || !now.isBefore(entry.windowStart().plus(window))) {
                    return new Entry(1, now);
                }
                return new Entry(entry.failures() + 1, entry.windowStart());
            });
            if (entries.size() > PURGE_THRESHOLD) {
                entries.values().removeIf(e -> !now.isBefore(e.windowStart().plus(window)));
            }
        }

        void clear(String key) {
            entries.remove(key);
        }
    }
}
