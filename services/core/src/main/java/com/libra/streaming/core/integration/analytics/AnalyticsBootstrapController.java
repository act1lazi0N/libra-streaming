package com.libra.streaming.core.integration.analytics;

import com.libra.streaming.core.api.DomainException;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * core-internal-analytics.v1 (RA02.4): Analytics' bootstrap reads. Reachable only through the internal service
 * chain with Analytics' pinned key and scope {@code analytics.bootstrap:read}; Nginx does not route {@code /internal/}.
 */
@RestController
@RequestMapping("/internal/v1/analytics")
public class AnalyticsBootstrapController {
    static final int DEFAULT_LIMIT = 200;
    private final AnalyticsBootstrapStore store;

    public AnalyticsBootstrapController(AnalyticsBootstrapStore store) { this.store = store; }

    @GetMapping("/snapshots/catalog")
    ResponseEntity<AnalyticsBootstrapStore.CatalogPage> catalog(@RequestParam(required = false) String after,
            @RequestParam(required = false) String limit) {
        return ok(store.catalog(uuid(after), limit(limit)));
    }

    @GetMapping("/snapshots/profiles")
    ResponseEntity<AnalyticsBootstrapStore.ProfilePage> profiles(@RequestParam(required = false) String after,
            @RequestParam(required = false) String limit) {
        return ok(store.profiles(uuid(after), limit(limit)));
    }

    @GetMapping("/events/playback")
    ResponseEntity<AnalyticsBootstrapStore.PlaybackPage> playback(@RequestParam(required = false) String after,
            @RequestParam(required = false) String limit) {
        return ok(store.playback(after, limit(limit)));
    }

    private static <T> ResponseEntity<T> ok(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }

    /** Lowercase dashed form only: the value is a cursor Core itself returned. */
    private static UUID uuid(String value) {
        if (value == null) { return null; }
        if (!value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) { throw DomainException.invalid(); }
        return UUID.fromString(value);
    }

    private static int limit(String value) {
        if (value == null) { return DEFAULT_LIMIT; }
        if (!value.matches("[1-9][0-9]{0,2}")) { throw DomainException.invalid(); }
        int limit = Integer.parseInt(value);
        if (limit > AnalyticsBootstrapStore.MAX_LIMIT) { throw DomainException.invalid(); }
        return limit;
    }
}
