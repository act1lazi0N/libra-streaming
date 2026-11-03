package com.libra.streaming.media.storage;

import com.libra.streaming.media.delivery.application.StreamObjects;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

/**
 * Streams objects from the private HLS area straight to the response with a fixed buffer: a segment is never held in
 * memory or written to scratch. Storage detail never crosses this boundary.
 */
@Component
public class S3StreamObjects implements StreamObjects {
    private static final int BUFFER = 64 * 1024;
    private final ObjectProvider<S3MediaStorage> storage;

    public S3StreamObjects(ObjectProvider<S3MediaStorage> storage) { this.storage = storage; }

    @Override
    public Optional<Opened> open(String key, long maxBytes) {
        var available = storage.getIfAvailable();
        if (available == null) { throw new Unavailable(); }
        ResponseInputStream<GetObjectResponse> body;
        try {
            body = available.open(S3MediaStorage.Area.HLS, key).orElse(null);
        } catch (MediaStorageException | IllegalArgumentException exception) {
            throw new Unavailable();
        }
        if (body == null) { return Optional.empty(); }
        Long length = body.response().contentLength();
        if (length == null || length < 1 || length > maxBytes) {
            body.abort();
            throw new Unavailable();
        }
        return Optional.of(new S3Opened(body, length));
    }

    private static final class S3Opened implements Opened {
        private final ResponseInputStream<GetObjectResponse> body;
        private final long length;
        private boolean drained;

        S3Opened(ResponseInputStream<GetObjectResponse> body, long length) {
            this.body = body;
            this.length = length;
        }

        @Override public long length() { return length; }

        @Override
        public void transferTo(OutputStream sink) throws IOException {
            byte[] buffer = new byte[BUFFER];
            long copied = 0;
            while (copied < length) {
                int read;
                try {
                    read = body.read(buffer, 0, (int) Math.min(buffer.length, length - copied));
                } catch (IOException | SdkException failure) {
                    throw new Unavailable();
                }
                // A body that ends before its stated length is a broken transfer, never a shorter object.
                if (read == -1) { throw new Unavailable(); }
                sink.write(buffer, 0, read);
                copied += read;
            }
            drained = true;
        }

        @Override
        public void close() {
            // Closing an unfinished stream may try to drain it; abort instead so a gone client frees the connection.
            if (!drained) {
                body.abort();
                return;
            }
            try { body.close(); } catch (IOException | SdkException ignored) { body.abort(); }
        }
    }
}
