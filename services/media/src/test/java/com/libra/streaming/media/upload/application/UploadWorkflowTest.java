package com.libra.streaming.media.upload.application;

import com.libra.streaming.media.upload.domain.UploadDescriptor;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UploadWorkflowTest {
    @Test
    void completionReturnsCommittedJobWhenAnotherCallerSubmitsBetweenReads() {
        var uploads = mock(UploadPersistence.class);
        var core = mock(CandidateBinding.class);
        var staging = mock(StagingInspector.class);
        var open = openSnapshot();
        var submitted = new UploadPersistence.UploadSnapshot(open.uploadId(), open.requestId(), open.contentId(),
                open.bindingId(), open.assetId(), 1, 1024, open.sha256(), "SUBMITTED", "QUEUED",
                UUID.randomUUID(), 0, null, open.expiresAt());
        when(uploads.read(open.uploadId())).thenReturn(open, submitted);
        when(uploads.grantSource(open.uploadId())).thenThrow(new UploadFailure("UPLOAD_STATE_CONFLICT"));
        var workflow = new UploadWorkflow(uploads, core, mock(UploadGrantSigner.class), staging);
        assertThat(workflow.complete(open.uploadId())).isEqualTo(submitted);
        verifyNoInteractions(core, staging);
        verify(uploads, never()).queue(any(), any(), anyLong());
    }

    @Test
    void aGrantPausedDuringCompletionIsNotReturnedAfterSubmission() {
        var uploads = mock(UploadPersistence.class);
        var open = openSnapshot();
        var source = new UploadPersistence.UploadSource(open, "staging/key");
        when(uploads.grantSource(open.uploadId())).thenReturn(source)
                .thenThrow(new UploadFailure("UPLOAD_STATE_CONFLICT"));
        var workflow = new UploadWorkflow(uploads, ignored -> {}, ignored -> new UploadGrantSigner.Grant(
                open.uploadId(), "PUT", "https://example.invalid/fixture", open.expiresAt(), java.util.Map.of()),
                ignored -> {});
        assertThatThrownBy(() -> workflow.issueUrl(open.uploadId())).hasMessage("UPLOAD_STATE_CONFLICT");
    }

    private static UploadPersistence.UploadSnapshot openSnapshot() {
        return new UploadPersistence.UploadSnapshot(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), 1, 1024, "a".repeat(64),
                "OPEN", "UPLOADING", null, 0, null, Instant.now().plusSeconds(3600));
    }

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
                        command.sha256(), "OPEN", "UPLOADING", null, 0, null, command.expiresAt()), "staging/key");
            }
            @Override public UploadSnapshot queue(UUID uploadId, UUID assetId, long assetVersion) {
                throw new AssertionError("queue reached");
            }
        };
        CandidateBinding denied = ignored -> { throw new IllegalStateException("not current"); };
        UploadGrantSigner signer = ignored -> { signs.incrementAndGet(); throw new AssertionError("sign reached"); };
        var workflow = new UploadWorkflow(persistence, denied, signer,
                ignored -> { throw new AssertionError("storage reached"); });

        assertThatThrownBy(() -> workflow.ensure(command)).hasMessage("not current");
        assertThatThrownBy(() -> workflow.issueUrl(command.uploadId())).hasMessage("not current");
        assertThat(reads).hasValue(0);
        assertThat(writes).hasValue(0);
        assertThat(signs).hasValue(0);
    }
}
