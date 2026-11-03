package com.libra.streaming.media.delivery.application;

import java.util.Optional;
import java.util.UUID;

/** Media's own record of one asset generation, read when a ticket asks for it. */
public interface DeliverableAssets {
    /** Empty when Media holds no such asset version. */
    Optional<Deliverable> find(UUID assetId, long assetVersion);

    /** {@code outputPrefix} and {@code masterKey} are set only once the asset is READY. */
    record Deliverable(UUID contentId, UUID bindingId, String state, String outputPrefix, String masterKey) {
        public boolean ready() { return "READY".equals(state); }
    }
}
