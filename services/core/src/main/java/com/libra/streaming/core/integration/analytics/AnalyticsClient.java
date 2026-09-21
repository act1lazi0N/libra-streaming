package com.libra.streaming.core.integration.analytics;

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
import static com.libra.streaming.core.integration.analytics.AnalyticsModels.*;
import static com.libra.streaming.core.integration.analytics.AnalyticsServiceTokens.Scope;

@Component
public class AnalyticsClient {
    private static final Duration BUDGET = Duration.ofMillis(500);
    private final AnalyticsProperties properties;
    private final AnalyticsServiceTokens tokens;
    private final ObjectMapper mapper;
    private final Validator validator;
    private final Semaphore inFlight = new Semaphore(16);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(BUDGET)
            .followRedirects(HttpClient.Redirect.NEVER).proxy(new ProxySelector() {
                @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                @Override public void connectFailed(URI uri, SocketAddress address, java.io.IOException exception) {}
            }).build();

    public AnalyticsClient(AnalyticsProperties properties, AnalyticsServiceTokens tokens, ObjectMapper mapper, Validator validator) {
        this.properties = properties; this.tokens = tokens; this.validator = validator;
        this.mapper = mapper.rebuild().disable(tools.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .disable(tools.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT).build();
    }
    public Result<CandidateResponse> recommendations(RecommendationQuery query, UUID correlationId) {
        return post("/internal/v1/recommendations/query", Scope.RECOMMENDATIONS, query, CandidateResponse.class, correlationId);
    }
    public Result<StatisticsResponse> statistics(StatisticsQuery query, UUID correlationId) {
        return post("/internal/v1/statistics/query", Scope.STATISTICS, query, StatisticsResponse.class, correlationId);
    }
    private <T> Result<T> post(String path, Scope scope, Object query, Class<T> type, UUID correlationId) {
        if (!properties.enabled()) { return Result.failed(Failure.DISABLED); }
        if (!inFlight.tryAcquire()) { return Result.failed(Failure.UNAVAILABLE); }
        long deadline = System.nanoTime() + BUDGET.toNanos();
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            var request = HttpRequest.newBuilder(properties.baseUrl().resolve(path)).timeout(BUDGET)
                    .header("Authorization", "Bearer " + tokens.issue(scope)).header("Content-Type", "application/json")
                    .header("Accept", "application/json").header("X-Correlation-ID", correlationId.toString())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(query))).build();
            if (deadline <= System.nanoTime()) { return Result.failed(Failure.UNAVAILABLE); }
            pending = http.sendAsync(request, ignored -> new LimitedBodySubscriber());
            var response = pending.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (response.statusCode() != 200) { return Result.failed(Failure.UNAVAILABLE); }
            String mediaType = response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].trim();
            if (!mediaType.equalsIgnoreCase("application/json")
                    || response.headers().firstValue("Content-Encoding").filter(value -> !value.equalsIgnoreCase("identity")).isPresent()) {
                return Result.failed(Failure.INVALID_RESPONSE);
            }
            T value = mapper.readValue(response.body(), type);
            if (value == null || !validator.validate(value).isEmpty()) { return Result.failed(Failure.INVALID_RESPONSE); }
            if (deadline <= System.nanoTime()) { return Result.failed(Failure.UNAVAILABLE); }
            return new Result<>(value, null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Result.failed(Failure.UNAVAILABLE);
        } catch (TimeoutException | ExecutionException exception) {
            return Result.failed(Failure.UNAVAILABLE);
        } catch (RuntimeException exception) {
            // Never surface downstream bodies, token material or connection details.
            return Result.failed(Failure.INVALID_RESPONSE);
        } finally {
            if (pending != null && !pending.isDone()) { pending.cancel(true); }
            inFlight.release();
        }
    }
    @PreDestroy void close() { http.shutdownNow(); }
}
