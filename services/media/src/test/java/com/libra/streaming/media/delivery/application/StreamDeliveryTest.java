package com.libra.streaming.media.delivery.application;

import com.libra.streaming.media.delivery.domain.PlaybackGrant;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** The delivery decision with in-memory ports: which checks run, in which order, and what reaches storage. */
class StreamDeliveryTest {
    final UUID content = UUID.randomUUID();
    final UUID binding = UUID.randomUUID();
    final UUID asset = UUID.randomUUID();
    final PlaybackGrant grant = new PlaybackGrant(UUID.randomUUID(), content, binding, asset, 1);
    final String prefix = "hls/" + UUID.randomUUID() + "/attempt-2";
    final Assets assets = new Assets();
    final Objects objects = new Objects();
    final StreamDelivery delivery = new StreamDelivery(assets, objects);

    @Test
    void aReadyGenerationOpensExactlyTheNamedObjectUnderItsOwnPrefix() throws Exception {
        assets.put(asset, 1, ready());
        objects.stored.put(prefix + "/segment-00003.ts", new byte[]{1, 2, 3});
        objects.stored.put(prefix + "/master.m3u8", new byte[]{4});

        try (var stream = delivery.open(grant, "segment-00003.ts")) {
            assertThat(stream.contentType()).isEqualTo("video/mp2t");
            var out = new ByteArrayOutputStream();
            stream.body().transferTo(out);
            assertThat(out.toByteArray()).containsExactly(1, 2, 3);
        }
        try (var stream = delivery.open(grant, "master.m3u8")) {
            assertThat(stream.contentType()).isEqualTo("application/vnd.apple.mpegurl");
        }
        assertThat(objects.opened).containsExactly(prefix + "/segment-00003.ts=268435456",
                prefix + "/master.m3u8=262144");
        assertThat(assets.lookups).containsExactly(asset + "/1", asset + "/1");
    }

    @Test
    void aNameOutsideTheVocabularyIsRefusedBeforeAnythingIsRead() {
        assets.put(asset, 1, ready());
        for (var name : List.of("../master.m3u8", "source.mp4", "segment-00300.ts", "")) {
            assertThatThrownBy(() -> delivery.open(grant, name)).isInstanceOf(StreamDelivery.NotFound.class);
        }
        assertThat(assets.lookups).isEmpty();
        assertThat(objects.opened).isEmpty();
    }

    @Test
    void aTicketForAnotherBindingOrContentIsDeniedAndAnUnknownOrUnreadyVersionIsNotFound() {
        assets.put(asset, 1, ready());
        var otherBinding = new PlaybackGrant(grant.sessionId(), content, UUID.randomUUID(), asset, 1);
        var otherContent = new PlaybackGrant(grant.sessionId(), UUID.randomUUID(), binding, asset, 1);
        assertThatThrownBy(() -> delivery.open(otherBinding, "master.m3u8")).isInstanceOf(StreamDelivery.Denied.class);
        assertThatThrownBy(() -> delivery.open(otherContent, "master.m3u8")).isInstanceOf(StreamDelivery.Denied.class);
        var otherVersion = new PlaybackGrant(grant.sessionId(), content, binding, asset, 2);
        assertThatThrownBy(() -> delivery.open(otherVersion, "master.m3u8")).isInstanceOf(StreamDelivery.NotFound.class);
        var otherAsset = new PlaybackGrant(grant.sessionId(), content, binding, UUID.randomUUID(), 1);
        assertThatThrownBy(() -> delivery.open(otherAsset, "master.m3u8")).isInstanceOf(StreamDelivery.NotFound.class);
        for (var state : List.of("UPLOADING", "QUEUED", "PROCESSING", "FAILED")) {
            // A record that is not READY is never delivered, even if objects exist under a prefix.
            assets.put(asset, 1, new DeliverableAssets.Deliverable(content, binding, state, prefix, prefix + "/master.m3u8"));
            assertThatThrownBy(() -> delivery.open(grant, "master.m3u8")).as(state)
                    .isInstanceOf(StreamDelivery.NotFound.class);
        }
        assertThat(objects.opened).isEmpty();
    }

    @Test
    void aBrokenReadyRecordIsUnavailableAndAMissingObjectIsNotFound() {
        for (var broken : List.of(
                new DeliverableAssets.Deliverable(content, binding, "READY", null, null),
                new DeliverableAssets.Deliverable(content, binding, "READY", prefix + "/", prefix + "//master.m3u8"),
                new DeliverableAssets.Deliverable(content, binding, "READY", prefix, "hls/other/attempt-1/master.m3u8"))) {
            assets.put(asset, 1, broken);
            assertThatThrownBy(() -> delivery.open(grant, "master.m3u8")).isInstanceOf(StreamObjects.Unavailable.class);
        }
        assertThat(objects.opened).isEmpty();
        assets.put(asset, 1, ready());
        assertThatThrownBy(() -> delivery.open(grant, "segment-00009.ts")).isInstanceOf(StreamDelivery.NotFound.class);
        objects.down = true;
        assertThatThrownBy(() -> delivery.open(grant, "master.m3u8")).isInstanceOf(StreamObjects.Unavailable.class);
    }

    DeliverableAssets.Deliverable ready() {
        return new DeliverableAssets.Deliverable(content, binding, "READY", prefix, prefix + "/master.m3u8");
    }

    static final class Assets implements DeliverableAssets {
        final Map<String, Deliverable> rows = new HashMap<>();
        final List<String> lookups = new ArrayList<>();

        void put(UUID asset, long version, Deliverable row) { rows.put(asset + "/" + version, row); }

        @Override public Optional<Deliverable> find(UUID assetId, long assetVersion) {
            lookups.add(assetId + "/" + assetVersion);
            return Optional.ofNullable(rows.get(assetId + "/" + assetVersion));
        }
    }

    static final class Objects implements StreamObjects {
        final Map<String, byte[]> stored = new HashMap<>();
        final List<String> opened = new ArrayList<>();
        boolean down;

        @Override public Optional<Opened> open(String key, long maxBytes) {
            if (down) { throw new Unavailable(); }
            opened.add(key + "=" + maxBytes);
            var bytes = stored.get(key);
            if (bytes == null) { return Optional.empty(); }
            return Optional.of(new Opened() {
                @Override public long length() { return bytes.length; }
                @Override public void transferTo(OutputStream sink) throws java.io.IOException { sink.write(bytes); }
                @Override public void close() {}
            });
        }
    }
}
