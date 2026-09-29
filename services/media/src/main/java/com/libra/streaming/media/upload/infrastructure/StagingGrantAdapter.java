package com.libra.streaming.media.upload.infrastructure;

import com.libra.streaming.media.upload.application.UploadFailure;
import com.libra.streaming.media.storage.StagingUploadSigner;
import com.libra.streaming.media.upload.application.UploadGrantSigner;
import com.libra.streaming.media.upload.application.UploadPersistence;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class StagingGrantAdapter implements UploadGrantSigner {
    private final ObjectProvider<StagingUploadSigner> signer;
    private final Clock clock;

    public StagingGrantAdapter(ObjectProvider<StagingUploadSigner> signer, Clock clock) {
        this.signer = signer;
        this.clock = clock;
    }

    @Override
    public Grant sign(UploadPersistence.UploadSource source) {
        StagingUploadSigner available = signer.getIfAvailable();
        if (available == null) { throw new UploadFailure("STORAGE_UNAVAILABLE"); }
        var current = source.snapshot();
        try {
            var signed = available.sign(current.uploadId(), source.stagingKey(), current.byteLength(),
                    current.sha256(), current.expiresAt(), clock.instant());
            return new Grant(signed.uploadId(), signed.method(), signed.url(), signed.expiresAt(),
                    signed.requiredHeaders());
        } catch (IllegalArgumentException exception) {
            throw new UploadFailure(current.expiresAt().isAfter(clock.instant())
                    ? "UPLOAD_STATE_CONFLICT" : "UPLOAD_EXPIRED");
        } catch (RuntimeException exception) {
            throw new UploadFailure("STORAGE_UNAVAILABLE");
        }
    }
}
