package com.libra.streaming.media.delivery.infrastructure;

import com.libra.streaming.media.delivery.application.StreamDelivery;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-instance stream limits ({@code libra.media.delivery.*}): concurrent streams in total and per playback session,
 * and the longest a single playlist or segment response may take. Validated by {@link StreamDelivery.Limits}.
 */
@ConfigurationProperties("libra.media.delivery")
public record StreamDeliveryProperties(int maxStreams, int maxStreamsPerSession, Duration transferTimeout) {
    public StreamDelivery.Limits limits() {
        return new StreamDelivery.Limits(maxStreams, maxStreamsPerSession, transferTimeout);
    }
}
