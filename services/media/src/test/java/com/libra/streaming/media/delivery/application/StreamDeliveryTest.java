package com.libra.streaming.media.delivery.application;

import com.libra.streaming.media.delivery.domain.PlaybackGrant;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
    static final Instant START = Instant.parse("2026-10-13T09:00:00Z");
    final PlaybackGrant grant = grant(UUID.randomUUID(), content, binding, asset, 1);
    final String prefix = "hls/" + UUID.randomUUID() + "/attempt-2";
    final Assets assets = new Assets();
    final Objects objects = new Objects();
    final MovingClock clock = new MovingClock(START);
    final StreamDelivery delivery = new StreamDelivery(assets, objects,
            new StreamDelivery.Limits(3, 2, Duration.ofSeconds(60)), clock);

    @Test
    void aReadyGenerationOpensExactlyTheNamedObjectUnderItsOwnPrefix() throws Exception {
        assets.put(asset, 1, ready());
        objects.stored.put(prefix + "/segment-00003.ts", new byte[]{1, 2, 3});
        objects.stored.put(prefix + "/master.m3u8", new byte[]{4});

        try (var stream = delivery.open(grant, "segment-00003.ts")) {
            assertThat(stream.contentType()).isEqualTo("video/mp2t");
            var out = new ByteArrayOutputStream();
            stream.transferTo(out);
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
        var otherBinding = grant(grant.sessionId(), content, UUID.randomUUID(), asset, 1);
        var otherContent = grant(grant.sessionId(), UUID.randomUUID(), binding, asset, 1);
        assertThatThrownBy(() -> delivery.open(otherBinding, "master.m3u8")).isInstanceOf(StreamDelivery.Denied.class);
        assertThatThrownBy(() -> delivery.open(otherContent, "master.m3u8")).isInstanceOf(StreamDelivery.Denied.class);
        var otherVersion = grant(grant.sessionId(), content, binding, asset, 2);
        assertThatThrownBy(() -> delivery.open(otherVersion, "master.m3u8")).isInstanceOf(StreamDelivery.NotFound.class);
        var otherAsset = grant(grant.sessionId(), content, binding, UUID.randomUUID(), 1);
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

    @Test
    void everyOpenedStreamHoldsItsSessionsShareUntilClosedAndEveryRefusalReleasesIt() {
        assets.put(asset, 1, ready());
        objects.stored.put(prefix + "/master.m3u8", new byte[]{1});
        var first = delivery.open(grant, "master.m3u8");
        var second = delivery.open(grant, "master.m3u8");
        assertThatThrownBy(() -> delivery.open(grant, "master.m3u8")).isInstanceOf(StreamAdmission.SessionBusy.class);
        // A bad name is refused before admission, so it is not found even for a session at its limit.
        assertThatThrownBy(() -> delivery.open(grant, "source.mp4")).isInstanceOf(StreamDelivery.NotFound.class);
        var other = grant(UUID.randomUUID(), content, binding, asset, 1);
        var third = delivery.open(other, "master.m3u8");
        assertThatThrownBy(() -> delivery.open(grant(UUID.randomUUID(), content, binding, asset, 1),
                "master.m3u8")).as("the instance limit").isInstanceOf(StreamAdmission.Busy.class);
        first.close();
        second.close();
        third.close();

        // Refusals after admission (missing object, unready or foreign record, storage down) release the share.
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> delivery.open(grant, "segment-00009.ts")).isInstanceOf(StreamDelivery.NotFound.class);
            assertThatThrownBy(() -> delivery.open(grant(grant.sessionId(), content, UUID.randomUUID(),
                    asset, 1), "master.m3u8")).isInstanceOf(StreamDelivery.Denied.class);
        }
        objects.down = true;
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> delivery.open(grant, "master.m3u8")).isInstanceOf(StreamObjects.Unavailable.class);
        }
        objects.down = false;
        delivery.open(grant, "master.m3u8").close();
        try (var a = delivery.open(grant, "master.m3u8"); var b = delivery.open(grant, "master.m3u8")) {
            assertThat(a.length() + b.length()).isEqualTo(2);
        }
    }

    @Test
    void aTransferStopsWritingOnceItsDeadlineHasPassedCountedFromAdmission() throws Exception {
        assets.put(asset, 1, ready());
        objects.stored.put(prefix + "/segment-00000.ts", new byte[]{1, 2, 3});
        objects.stored.put(prefix + "/segment-00002.ts", new byte[]{1, 2, 3, 4});
        objects.chunked = true;
        // A slow player: each byte it accepts takes 25 s.
        var slow = new ByteArrayOutputStream() {
            @Override public synchronized void write(byte[] bytes, int offset, int length) {
                super.write(bytes, offset, length);
                clock.advance(Duration.ofSeconds(25));
            }
        };
        try (var stream = delivery.open(grant, "segment-00002.ts")) {
            assertThatThrownBy(() -> stream.transferTo(slow)).isInstanceOf(StreamDelivery.Expired.class);
        }
        assertThat(slow.toByteArray()).as("bytes at 0, 25 and 50 s; none at 75 s").containsExactly(1, 2, 3);

        objects.stored.put(prefix + "/segment-00001.ts", new byte[]{1, 2, 3, 4});
        var slower = new ByteArrayOutputStream() {
            @Override public synchronized void write(byte[] bytes, int offset, int length) {
                super.write(bytes, offset, length);
                clock.advance(Duration.ofSeconds(30));
            }
        };
        try (var stream = delivery.open(grant, "segment-00001.ts")) {
            assertThatThrownBy(() -> stream.transferTo(slower)).isInstanceOf(StreamDelivery.Expired.class);
        }
        assertThat(slower.toByteArray()).as("bytes at 0 s and 30 s; none at the 60 s deadline").containsExactly(1, 2);

        // The budget starts at admission: time spent before the copy counts, and equality is expired.
        var stream = delivery.open(grant, "segment-00000.ts");
        clock.advance(Duration.ofSeconds(60));
        var late = new ByteArrayOutputStream();
        assertThatThrownBy(() -> stream.transferTo(late)).isInstanceOf(StreamDelivery.Expired.class);
        assertThat(late.size()).isZero();
        stream.close();
        try (var fresh = delivery.open(grant, "segment-00000.ts")) {
            clock.advance(Duration.ofSeconds(59));
            var out = new ByteArrayOutputStream();
            fresh.transferTo(out);
            assertThat(out.toByteArray()).containsExactly(1, 2, 3);
        }
    }

    @Test
    void limitsAreValidated() {
        for (var limits : new Object[][]{{0, 1, 60L}, {2, 0, 60L}, {2, 3, 60L}, {2, 1, 0L}, {2, 1, 601L}}) {
            assertThatThrownBy(() -> new StreamDelivery.Limits((int) limits[0], (int) limits[1],
                    Duration.ofSeconds((long) limits[2]))).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new StreamDelivery.Limits(2, 1, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new StreamDelivery.Limits(1, 1, Duration.ofSeconds(1))).isNotNull();
        assertThat(new StreamDelivery.Limits(64, 4, Duration.ofMinutes(10))).isNotNull();
    }

    /** A grant whose ticket outlives every test here (five minutes after {@link #START}). */
    static PlaybackGrant grant(UUID session, UUID content, UUID binding, UUID asset, long version) {
        return new PlaybackGrant(session, content, binding, asset, version, START.plusSeconds(300));
    }

    @Test
    void aTransferNeverOutlivesItsTicketEvenWithinTheTransferTimeout() throws Exception {
        assets.put(asset, 1, ready());
        objects.stored.put(prefix + "/segment-00004.ts", new byte[]{1, 2, 3, 4});
        objects.chunked = true;
        var expiring = new PlaybackGrant(grant.sessionId(), content, binding, asset, 1, clock.instant().plusSeconds(2));
        var slow = new ByteArrayOutputStream() {
            @Override public synchronized void write(byte[] bytes, int offset, int length) {
                super.write(bytes, offset, length);
                clock.advance(Duration.ofSeconds(1));
            }
        };
        try (var stream = delivery.open(expiring, "segment-00004.ts")) {
            assertThatThrownBy(() -> stream.transferTo(slow)).isInstanceOf(StreamDelivery.Expired.class);
        }
        assertThat(slow.toByteArray()).as("bytes at 0 and 1 s; none at the ticket expiry").containsExactly(1, 2);
    }

    @Test
    void anErrorAfterAdmissionStillGivesTheSessionsShareBack() {
        assets.put(asset, 1, ready());
        objects.stored.put(prefix + "/master.m3u8", new byte[]{1});
        objects.error = true;
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> delivery.open(grant, "master.m3u8")).isInstanceOf(StackOverflowError.class);
        }
        objects.error = false;
        try (var a = delivery.open(grant, "master.m3u8"); var b = delivery.open(grant, "master.m3u8")) {
            assertThat(a.length() + b.length()).isEqualTo(2);
        }
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
        boolean chunked;
        boolean error;

        @Override public Optional<Opened> open(String key, long maxBytes) {
            if (down) { throw new Unavailable(); }
            if (error) { throw new StackOverflowError(); }
            opened.add(key + "=" + maxBytes);
            var bytes = stored.get(key);
            if (bytes == null) { return Optional.empty(); }
            return Optional.of(new Opened() {
                @Override public long length() { return bytes.length; }
                @Override public void transferTo(OutputStream sink) throws java.io.IOException {
                    if (!chunked) { sink.write(bytes); return; }
                    for (byte b : bytes) { sink.write(new byte[]{b}, 0, 1); }
                }
                @Override public void close() {}
            });
        }
    }

    static final class MovingClock extends Clock {
        private Instant now;

        MovingClock(Instant now) { this.now = now; }

        void advance(Duration duration) { now = now.plus(duration); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
