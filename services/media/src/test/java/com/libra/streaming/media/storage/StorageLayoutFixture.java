package com.libra.streaming.media.storage;

import com.libra.streaming.media.lifecycle.application.CleanupStorage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.services.s3.S3Client;

/** The real storage adapters under another layout than the running context's, as after a configuration change. */
public final class StorageLayoutFixture {
    private StorageLayoutFixture() {}

    public static MediaStorageProperties withPrefixes(MediaStorageProperties p, String sourcePrefix, String hlsPrefix,
            Path scratch) {
        return new MediaStorageProperties(p.internalEndpoint(), p.browserEndpoint(), p.browserOrigin(), p.region(),
                p.accessKey(), p.secretKey(), p.sourceBucket(), p.hlsBucket(), p.stagingPrefix(), sourcePrefix,
                hlsPrefix, p.connectTimeout(), p.requestTimeout(), p.maxObjectBytes(), scratch, p.allowHttp(), false,
                false);
    }

    /** The startup check of a process configured with {@code properties}. */
    public static void verify(JdbcTemplate jdbc, MediaStorageProperties properties) {
        new MediaStorageLayout(jdbc, properties);
    }

    /** Cleanup under {@code properties}; closing it releases the scratch space the storage adapter holds. */
    public static Moved cleanupStorage(S3Client client, MediaStorageProperties properties) throws IOException {
        var storage = new S3MediaStorage(client, properties);
        var beans = new StaticListableBeanFactory(Map.of("storage", storage, "properties", properties));
        return new Moved(new S3CleanupStorage(beans.getBeanProvider(S3MediaStorage.class),
                beans.getBeanProvider(MediaStorageProperties.class)), storage);
    }

    public record Moved(CleanupStorage storage, AutoCloseable resources) implements AutoCloseable {
        @Override public void close() throws Exception { resources.close(); }
    }
}
