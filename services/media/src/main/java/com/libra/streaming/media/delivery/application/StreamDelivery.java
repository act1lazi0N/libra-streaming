package com.libra.streaming.media.delivery.application;

import com.libra.streaming.media.delivery.domain.PlaybackGrant;
import com.libra.streaming.media.delivery.domain.StreamName;
import java.util.Objects;

/**
 * Resolves one protected stream request. The object key is derived only from Media's READY record for the asset version
 * the ticket names, after that record is shown to belong to the ticket's content binding, and from a name in the closed
 * {@link StreamName} vocabulary. The browser never supplies a key, prefix, bucket or version.
 */
public final class StreamDelivery {
    private final DeliverableAssets assets;
    private final StreamObjects objects;

    public StreamDelivery(DeliverableAssets assets, StreamObjects objects) {
        this.assets = Objects.requireNonNull(assets);
        this.objects = Objects.requireNonNull(objects);
    }

    /**
     * The opened object, which the caller must close. A name outside the vocabulary, an unknown or not yet READY asset
     * version, or a missing object is {@link NotFound}; a READY record for another content binding is
     * {@link Denied}; storage trouble is {@link StreamObjects.Unavailable}. Storage is touched only after every check.
     */
    public Stream open(PlaybackGrant grant, String requestedName) {
        Objects.requireNonNull(grant);
        var name = StreamName.parse(requestedName).orElseThrow(NotFound::new);
        var asset = assets.find(grant.assetId(), grant.assetVersion()).orElseThrow(NotFound::new);
        if (!grant.contentId().equals(asset.contentId()) || !grant.bindingId().equals(asset.bindingId())) {
            throw new Denied();
        }
        if (!asset.ready()) { throw new NotFound(); }
        String prefix = asset.outputPrefix();
        // The database guarantees both for READY; a disagreement is a broken record, never a reason to guess a key.
        if (prefix == null || prefix.isBlank() || prefix.endsWith("/")
                || !(prefix + "/master.m3u8").equals(asset.masterKey())) {
            throw new StreamObjects.Unavailable();
        }
        var body = objects.open(prefix + "/" + name.value(), name.kind().maxBytes()).orElseThrow(NotFound::new);
        return new Stream(name, body);
    }

    /** One opened stream object and the name it was requested by. */
    public record Stream(StreamName name, StreamObjects.Opened body) implements AutoCloseable {
        public String contentType() { return name.kind().contentType(); }
        @Override public void close() { body.close(); }
    }

    /** Nothing deliverable under that name for this grant. Carries no detail. */
    public static final class NotFound extends RuntimeException {
        public NotFound() { super("NOT_FOUND", null, false, false); }
    }

    /** The ticket names an asset version that Media records for another content binding. */
    public static final class Denied extends RuntimeException {
        public Denied() { super("ACCESS_DENIED", null, false, false); }
    }
}
