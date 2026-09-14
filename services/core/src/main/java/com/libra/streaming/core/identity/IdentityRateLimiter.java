package com.libra.streaming.core.identity;

import java.time.Clock;
import java.time.Duration;
import java.sql.Timestamp;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class IdentityRateLimiter {
    private final JdbcTemplate jdbc;
    private final IdentitySecrets secrets;
    private final Clock clock;

    public IdentityRateLimiter(JdbcTemplate jdbc, IdentitySecrets secrets, Clock clock) {
        this.jdbc = jdbc;
        this.secrets = secrets;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = IdentityException.class)
    public void check(String scope, String key, int maximum, Duration window) {
        var now = clock.instant();
        var bucket = jdbc.queryForMap("""
                INSERT INTO identity_rate_limits (bucket_key, window_start, attempts) VALUES (?, ?, 1)
                ON CONFLICT (bucket_key) DO UPDATE SET
                    attempts = CASE WHEN identity_rate_limits.window_start <= ? THEN 1
                        ELSE LEAST(identity_rate_limits.attempts + 1, ?) END,
                    window_start = CASE WHEN identity_rate_limits.window_start <= ? THEN EXCLUDED.window_start
                        ELSE identity_rate_limits.window_start END
                RETURNING attempts, window_start
                """, secrets.fingerprint(scope + ":" + key), Timestamp.from(now),
                Timestamp.from(now.minus(window)), maximum + 1, Timestamp.from(now.minus(window)));
        if (((Number) bucket.get("attempts")).intValue() > maximum) {
            var remaining = Duration.between(now, ((Timestamp) bucket.get("window_start")).toInstant().plus(window));
            throw new IdentityException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    Math.max(1, (remaining.toMillis() + 999) / 1000));
        }
    }
}
