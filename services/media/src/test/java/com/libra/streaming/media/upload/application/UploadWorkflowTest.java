package com.libra.streaming.media.upload.application;

import com.libra.streaming.media.upload.domain.UploadDescriptor;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class UploadWorkflowTest {
    @Test
    void rejectedCoreCandidateCannotReachMediaWriteOrUrlSigner() {
        var command = new UploadDescriptor(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), 1, 1024, "a".repeat(64), Instant.now().plusSeconds(3600));
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger writes = new AtomicInteger();
        AtomicInteger signs = new AtomicInteger();
        UploadPersistence persistence = new UploadPersistence() {
            @Override public UploadSnapshot ensure(UploadDescriptor ignored) {
                writes.incrementAndGet();
                throw new AssertionError("write reached");
            }
            @Override public UploadSnapshot read(UUID ignored) {
                reads.incrementAndGet();
                throw new AssertionError("read reached");
            }
            @Override public UploadSource grantSource(UUID ignored) {
                return new UploadSource(new UploadSnapshot(command.uploadId(), command.requestId(), command.contentId(),
                        command.bindingId(), command.assetId(), command.assetVersion(), command.byteLength(),
                        command.sha256(), "OPEN", "UPLOADING", null, 0, command.expiresAt()), "staging/key");
            }
        };
        CandidateBinding denied = ignored -> { throw new IllegalStateException("not current"); };
        UploadGrantSigner signer = ignored -> { signs.incrementAndGet(); throw new AssertionError("sign reached"); };
        var workflow = new UploadWorkflow(persistence, denied, signer);

        assertThatThrownBy(() -> workflow.ensure(command)).hasMessage("not current");
        assertThatThrownBy(() -> workflow.issueUrl(command.uploadId())).hasMessage("not current");
        assertThat(reads).hasValue(0);
        assertThat(writes).hasValue(0);
        assertThat(signs).hasValue(0);
    }
}
