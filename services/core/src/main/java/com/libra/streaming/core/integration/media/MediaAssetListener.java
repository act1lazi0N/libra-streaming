package com.libra.streaming.core.integration.media;

import com.libra.streaming.core.catalog.MediaProjectionService;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;

@Component
public class MediaAssetListener {
    private final MediaProjectionService projections;
    private final MediaEventDecoder decoder;

    public MediaAssetListener(MediaProjectionService projections, MediaEventDecoder decoder) {
        this.projections = projections;
        this.decoder = decoder;
    }

    @KafkaListener(id = "core-media-assets", topics = "media.assets.v1", groupId = "core-media-projection-v1",
            containerFactory = "mediaListenerFactory", autoStartup = "${libra.catalog.media-listener-enabled:true}")
    public void receive(ConsumerRecord<String, String> record) {
        try { projections.accept(record.key(), decoder.decode(record.key(), record.value())); }
        catch (RuntimeException exception) {
            // Kafka error handling may log failures. Never attach parser input or SQL exception text.
            if (isTransient(exception)) { throw new MediaEventRetryableException(); }
            throw new IllegalStateException("Media event processing failed");
        }
    }

    private static boolean isTransient(RuntimeException exception) {
        // Spring also translates program limits as resource failures; retrying cannot resolve those limits.
        if (MediaFailures.anyCause(exception, cause -> cause instanceof SQLException sql
                && sql.getSQLState() != null && sql.getSQLState().startsWith("54"))) { return false; }
        return MediaFailures.anyCause(exception, cause -> {
            if (cause instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && (state.startsWith("08") || state.startsWith("53") || state.startsWith("57P0")
                        || state.equals("40001") || state.equals("40P01") || state.equals("55P03"))) { return true; }
            }
            return cause instanceof TransientDataAccessException || cause instanceof RecoverableDataAccessException
                    || cause instanceof DataAccessResourceFailureException || cause instanceof CannotCreateTransactionException
                    || cause instanceof SQLTransientException || cause instanceof SQLRecoverableException;
        });
    }
}
