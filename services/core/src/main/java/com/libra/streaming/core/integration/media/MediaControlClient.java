package com.libra.streaming.core.integration.media;

import jakarta.annotation.PreDestroy;
import jakarta.validation.Validator;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import static com.libra.streaming.core.integration.media.MediaControlModels.*;
import static com.libra.streaming.core.integration.media.MediaControlTokens.Scope;

/** No automatic retry: a transport failure may follow a committed remote mutation. */
@Component
public class MediaControlClient {
    private static final Duration BUDGET = Duration.ofSeconds(2);
    private final MediaControlProperties properties;
    private final MediaControlTokens tokens;
    private final ObjectMapper mapper;
    private final Validator validator;
    private final Semaphore inFlight = new Semaphore(16);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500))
            .followRedirects(HttpClient.Redirect.NEVER).proxy(new ProxySelector() {
                @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                @Override public void connectFailed(URI uri, SocketAddress address, java.io.IOException exception) {}
            }).build();

    public MediaControlClient(MediaControlProperties properties, MediaControlTokens tokens, ObjectMapper mapper, Validator validator) {
        this.properties = properties; this.tokens = tokens; this.validator = validator;
        this.mapper = mapper.rebuild().disable(tools.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .disable(tools.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    }

    public Result read(UUID uploadId, UUID correlationId) { return exchange(uploadId, null, correlationId, false); }

    public Result ensure(UUID uploadId, EnsureUpload intent, UUID correlationId) {
        if (intent == null || !validator.validate(intent).isEmpty()) {
            throw new IllegalArgumentException("Valid committed upload context is required");
        }
        return exchange(uploadId, intent, correlationId, false);
    }

    public Result complete(UUID uploadId, UUID correlationId) {
        return exchange(uploadId, null, correlationId, true);
    }

    public GrantResult issueUrl(UUID uploadId, UUID correlationId) {
        if (uploadId == null || correlationId == null) { throw new IllegalArgumentException("Operation identity is required"); }
        if (!properties.enabled()) { return GrantResult.failed(Failure.DISABLED); }
        if (!inFlight.tryAcquire()) { return GrantResult.failed(Failure.UNAVAILABLE); }
        long deadline = System.nanoTime() + BUDGET.toNanos();
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            var request = HttpRequest.newBuilder(properties.origin().resolve(
                    "/internal/v1/uploads/" + uploadId + "/upload-url"))
                    .timeout(BUDGET).header("Authorization", "Bearer " + tokens.issue(Scope.WRITE))
                    .header("Accept", "application/json").header("X-Correlation-ID", correlationId.toString())
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            pending = http.sendAsync(request, ignored -> new LimitedMediaBodySubscriber());
            var response = pending.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (response.statusCode() != 200) {
                return GrantResult.failed(switch (response.statusCode()) {
                    case 404 -> Failure.NOT_FOUND;
                    case 409 -> Failure.CONFLICT;
                    case 410 -> Failure.EXPIRED;
                    default -> Failure.UNAVAILABLE;
                });
            }
            String type = response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].trim();
            if (!type.equalsIgnoreCase("application/json") || response.headers().firstValue("Content-Encoding")
                    .filter(value -> !value.equalsIgnoreCase("identity")).isPresent()) {
                return GrantResult.failed(Failure.INVALID_RESPONSE);
            }
            UploadUrl value = mapper.readValue(response.body(), UploadUrl.class);
            if (value == null || !validator.validate(value).isEmpty() || !uploadId.equals(value.uploadId())
                    || !"PUT".equals(value.method()) || value.url().length() > 4096
                    || !value.requiredHeaders().containsKey("content-type")
                    && !value.requiredHeaders().containsKey("Content-Type")) {
                return GrantResult.failed(Failure.INVALID_RESPONSE);
            }
            URI uri = URI.create(value.url());
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                    || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    || deadline <= System.nanoTime()) {
                return GrantResult.failed(Failure.INVALID_RESPONSE);
            }
            return new GrantResult(value, null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return GrantResult.failed(Failure.UNAVAILABLE);
        } catch (TimeoutException | ExecutionException exception) {
            return GrantResult.failed(Failure.UNAVAILABLE);
        } catch (RuntimeException exception) {
            return GrantResult.failed(Failure.INVALID_RESPONSE);
        } finally {
            if (pending != null && !pending.isDone()) { pending.cancel(true); }
            inFlight.release();
        }
    }

    private Result exchange(UUID uploadId, EnsureUpload intent, UUID correlationId, boolean complete) {
        if (uploadId == null || correlationId == null) { throw new IllegalArgumentException("Operation identity is required"); }
        if (!properties.enabled()) { return Result.failed(Failure.DISABLED); }
        if (!inFlight.tryAcquire()) { return Result.failed(Failure.UNAVAILABLE); }
        long deadline = System.nanoTime() + BUDGET.toNanos();
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            var request = HttpRequest.newBuilder(properties.origin().resolve("/internal/v1/uploads/" + uploadId
                    + (complete ? "/complete" : "")))
                    .timeout(BUDGET).header("Authorization", "Bearer " + tokens.issue(
                            intent == null && !complete ? Scope.READ : Scope.WRITE))
                    .header("Accept", "application/json").header("X-Correlation-ID", correlationId.toString());
            if (complete) { request.POST(HttpRequest.BodyPublishers.noBody()); }
            else if (intent == null) { request.GET(); }
            else { request.header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(intent))); }
            pending = http.sendAsync(request.build(), ignored -> new LimitedMediaBodySubscriber());
            var response = pending.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (response.statusCode() != (complete ? 202 : 200)
                    && !(intent != null && response.statusCode() == 201)) {
                return Result.failed(switch (response.statusCode()) {
                    case 401, 403 -> Failure.ACCESS_DENIED;
                    case 404 -> Failure.NOT_FOUND;
                    case 409 -> complete && sourceMissing(response.body()) ? Failure.SOURCE_MISSING : Failure.CONFLICT;
                    case 410 -> Failure.EXPIRED;
                    default -> Failure.UNAVAILABLE;
                });
            }
            String type = response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].trim();
            if (!type.equalsIgnoreCase("application/json") || response.headers().firstValue("Content-Encoding")
                    .filter(value -> !value.equalsIgnoreCase("identity")).isPresent()) {
                return Result.failed(Failure.INVALID_RESPONSE);
            }
            Status value = mapper.readValue(response.body(), Status.class);
            if (value == null || !validator.validate(value).isEmpty() || !value.consistent() || !uploadId.equals(value.uploadId())
                    || (intent != null && (!intent.contentId().equals(value.contentId()) || !intent.bindingId().equals(value.bindingId())
                        || !intent.assetId().equals(value.assetId()) || intent.assetVersion() != value.assetVersion()
                        || !intent.expiresAt().equals(value.expiresAt())))) {
                return Result.failed(Failure.INVALID_RESPONSE);
            }
            if (deadline <= System.nanoTime()) { return Result.failed(Failure.UNAVAILABLE); }
            return new Result(value, null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Result.failed(Failure.UNAVAILABLE);
        } catch (TimeoutException | ExecutionException exception) {
            return Result.failed(Failure.UNAVAILABLE);
        } catch (RuntimeException exception) {
            return Result.failed(Failure.INVALID_RESPONSE);
        } finally {
            if (pending != null && !pending.isDone()) { pending.cancel(true); }
            inFlight.release();
        }
    }
    private boolean sourceMissing(byte[] body) {
        try {
            return "SOURCE_MISSING".equals(mapper.readTree(body).path("code").asString());
        } catch (RuntimeException exception) { return false; }
    }
    @PreDestroy void close() { http.shutdownNow(); }
}
