package com.libra.streaming.core.integration.analytics;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Cancels the stream before buffering more than the protocol limit, including chunked bodies. */
final class LimitedBodySubscriber implements BodySubscriber<byte[]> {
    static final int MAX_BYTES = 65536;
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
            body.completeExceptionally(new IllegalArgumentException("Analytics response exceeds size limit"));
            return;
        }
        for (var buffer : buffers) {
            byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
        }
        subscription.request(1);
    }
    @Override public void onError(Throwable error) { body.completeExceptionally(error); }
    @Override public void onComplete() { body.complete(bytes.toByteArray()); }
}
