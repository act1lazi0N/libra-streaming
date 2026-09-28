package com.libra.streaming.media.persistence;

import java.time.Instant;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Test-only bridge; keep the production queue repository package-private. */
public class MediaSecurityQueueProbe {
    private final MediaUploadStore store;
    private final TransactionTemplate transaction;

    public MediaSecurityQueueProbe(MediaUploadStore store, PlatformTransactionManager transactions) {
        this.store = store;
        this.transaction = new TransactionTemplate(transactions);
    }

    public void queue(MediaPersistenceService.Ensure command) {
        transaction.executeWithoutResult(status -> store.queue(command.uploadId(), command.assetId(),
                command.assetVersion(), UUID.randomUUID(), Instant.now()));
    }
}
