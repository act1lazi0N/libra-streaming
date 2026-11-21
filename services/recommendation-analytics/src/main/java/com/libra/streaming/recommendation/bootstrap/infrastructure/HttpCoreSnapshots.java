package com.libra.streaming.recommendation.bootstrap.infrastructure;

import static com.libra.streaming.recommendation.ingestion.infrastructure.ContractJson.*;

import com.libra.streaming.recommendation.bootstrap.application.CoreSnapshots;
import com.libra.streaming.recommendation.bootstrap.domain.SnapshotPages;
import com.libra.streaming.recommendation.catalog.domain.CatalogNode;
import com.libra.streaming.recommendation.ingestion.domain.CatalogChange;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

public final class HttpCoreSnapshots implements CoreSnapshots {
    private static final Pattern CURSOR = Pattern.compile(
            "[0-9]{1,19}\\.[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern JSON_TYPE = Pattern.compile(
            "application/json(?:\\s*;\\s*charset=utf-8)?", Pattern.CASE_INSENSITIVE);
    private static final Set<String> PROFILE = Set.of("profileId", "accountId", "version", "deleted");
    private static final Set<String> CATALOG = Set.of("contentId", "version", "kind", "parentId", "ordinal",
            "published", "active", "publishedAt");
    private final BootstrapProperties properties;
    private final BootstrapTokens tokens;
    private final Clock clock;
    private final Duration requestTimeout;
    private final HttpClient http;
    private final ObjectMapper mapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public HttpCoreSnapshots(BootstrapProperties properties, BootstrapTokens tokens, Clock clock) {
        this(properties, tokens, clock, Duration.ofSeconds(2), Duration.ofSeconds(10));
    }

    HttpCoreSnapshots(BootstrapProperties properties, BootstrapTokens tokens, Clock clock,
            Duration connectTimeout, Duration requestTimeout) {
        this.properties = properties;
        this.tokens = tokens;
        this.clock = clock;
        this.requestTimeout = requestTimeout;
        this.http = HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1).build();
    }

    @Override
    public SnapshotPages.ProfilePage profiles(UUID after, int limit) {
        JsonNode root = fetch("snapshots/profiles", after == null ? null : after.toString(), limit);
        try {
            JsonNode items = page(root, "items", limit);
            var values = new ArrayList<SnapshotPages.ProfileItem>();
            String previous = after == null ? null : after.toString();
            for (JsonNode item : items.values()) {
                object(item, PROFILE);
                UUID id = uuid(item.get("profileId"), "PROFILE_ID");
                previous = ordered(id, previous);
                JsonNode deleted = item.get("deleted");
                require(deleted.isBoolean());
                values.add(new SnapshotPages.ProfileItem(id, uuid(item.get("accountId"), "ACCOUNT_ID"),
                        integer(item.get("version"), 1, Long.MAX_VALUE, "VERSION"), deleted.booleanValue()));
            }
            return new SnapshotPages.ProfilePage(values, nextId(root, previous));
        } catch (RuntimeException invalid) { throw failure("CORE_BODY"); }
    }

    @Override
    public SnapshotPages.CatalogPage catalog(UUID after, int limit) {
        JsonNode root = fetch("snapshots/catalog", after == null ? null : after.toString(), limit);
        try {
            JsonNode items = page(root, "items", limit);
            var values = new ArrayList<CatalogNode>();
            String previous = after == null ? null : after.toString();
            for (JsonNode item : items.values()) {
                object(item, CATALOG);
                UUID id = uuid(item.get("contentId"), "CONTENT_ID");
                previous = ordered(id, previous);
                var kind = CatalogChange.Kind.valueOf(text(item.get("kind"), "KIND"));
                UUID parent = item.get("parentId").isNull() ? null : uuid(item.get("parentId"), "PARENT_ID");
                Integer ordinal = item.get("ordinal").isNull() ? null
                        : (int) integer(item.get("ordinal"), 1, Integer.MAX_VALUE, "ORDINAL");
                var published = item.get("published").isNull() ? null : revision(item.get("published"));
                var active = item.get("active").isNull() ? null : binding(item.get("active"));
                var publishedAt = item.get("publishedAt").isNull() ? null
                        : instant(item.get("publishedAt"), "PUBLISHED_AT");
                require(kind.root() == (parent == null) && kind.root() == (ordinal == null));
                require(active == null || active.contentId().equals(id));
                values.add(new CatalogNode(id, integer(item.get("version"), 1, Long.MAX_VALUE, "VERSION"), kind,
                        parent, ordinal, published, active, publishedAt, false, clock.instant()));
            }
            return new SnapshotPages.CatalogPage(values, nextId(root, previous));
        } catch (RuntimeException invalid) { throw failure("CORE_BODY"); }
    }

    @Override
    public SnapshotPages.PlaybackPage playback(String after, int limit) {
        if (after != null && !CURSOR.matcher(after).matches()) { throw failure("CORE_BODY"); }
        JsonNode root = fetch("events/playback", after, limit);
        try {
            JsonNode items = page(root, "events", limit);
            var events = new ArrayList<SnapshotPages.ReplayedEvent>();
            for (JsonNode item : items.values()) {
                require(item.isObject());
                events.add(new SnapshotPages.ReplayedEvent(text(item.get("aggregateId"), "AGGREGATE_ID"),
                        mapper.writeValueAsString(item)));
            }
            String next = root.get("next").isNull() ? null : text(root.get("next"), "NEXT");
            require(next == null || CURSOR.matcher(next).matches());
            return new SnapshotPages.PlaybackPage(events, next);
        } catch (RuntimeException invalid) { throw failure("CORE_BODY"); }
    }

    private JsonNode fetch(String path, String after, int limit) {
        if (!properties.enabled()) { throw failure("CORE_TRANSPORT"); }
        require(limit >= 1 && limit <= 500);
        // Both accepted cursor formats contain only URI-safe characters.
        URI uri = properties.coreBaseUrl().resolve("/internal/v1/analytics/" + path
                + "?" + (after == null ? "" : "after=" + after + "&") + "limit=" + limit);
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            var request = HttpRequest.newBuilder(uri).timeout(requestTimeout)
                    .header("Authorization", "Bearer " + tokens.issue()).header("Accept", "application/json")
                    .GET().build();
            pending = http.sendAsync(request, info -> {
                if (info.statusCode() != 200) { throw failure("CORE_STATUS"); }
                var types = info.headers().allValues("Content-Type");
                if (types.size() != 1 || !JSON_TYPE.matcher(types.getFirst().trim()).matches()) {
                    throw failure("CORE_BODY");
                }
                return new LimitedBodySubscriber();
            });
            var response = pending.get(requestTimeout.toNanos(), TimeUnit.NANOSECONDS);
            try { return mapper.readTree(response.body()); }
            catch (RuntimeException invalid) { throw failure("CORE_BODY"); }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw failure("CORE_TRANSPORT");
        } catch (TimeoutException timeout) {
            throw failure("CORE_TRANSPORT");
        } catch (ExecutionException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof SnapshotUnavailableException known) { throw known; }
            }
            throw failure("CORE_TRANSPORT");
        } finally {
            if (pending != null && !pending.isDone()) { pending.cancel(true); }
        }
    }

    private static JsonNode page(JsonNode root, String field, int limit) {
        object(root, Set.of(field, "next"));
        JsonNode items = root.get(field);
        require(items.isArray() && items.size() <= limit);
        require(root.get("next").isNull() || items.size() == limit);
        return items;
    }

    private static String ordered(UUID id, String previous) {
        String current = id.toString();
        require(previous == null || current.compareTo(previous) > 0);
        return current;
    }

    private static UUID nextId(JsonNode root, String last) {
        if (root.get("next").isNull()) { return null; }
        UUID next = uuid(root.get("next"), "NEXT");
        require(next.toString().equals(last));
        return next;
    }

    private static void object(JsonNode node, Set<String> fields) {
        require(node != null && node.isObject());
        closed(node, fields, "OBJECT");
    }

    private static void require(boolean valid) { if (!valid) { throw failure("CORE_BODY"); } }

    private static SnapshotUnavailableException failure(String code) { return new SnapshotUnavailableException(code); }

    @PreDestroy
    public void close() { http.shutdownNow(); }

    /** Cancels before buffering bytes above the limit, including chunked responses. */
    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private static final int MAX_BYTES = 4 * 1024 * 1024;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private Flow.Subscription subscription;

        @Override public CompletionStage<byte[]> getBody() { return body; }

        @Override public void onSubscribe(Flow.Subscription incoming) {
            if (subscription != null) { incoming.cancel(); return; }
            subscription = incoming;
            incoming.request(1);
        }

        @Override public void onNext(List<ByteBuffer> buffers) {
            long count = bytes.size();
            for (var buffer : buffers) { count += buffer.remaining(); }
            if (count > MAX_BYTES) {
                subscription.cancel();
                body.completeExceptionally(failure("CORE_BODY"));
                return;
            }
            for (var buffer : buffers) {
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }

        @Override public void onError(Throwable error) { body.completeExceptionally(error); }
        @Override public void onComplete() { body.complete(bytes.toByteArray()); }
    }
}
