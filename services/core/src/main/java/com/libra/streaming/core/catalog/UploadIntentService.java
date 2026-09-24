package com.libra.streaming.core.catalog;

import com.libra.streaming.core.api.DomainException;
import com.libra.streaming.core.identity.IdentityAccess;
import com.libra.streaming.core.identity.IdentityPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.libra.streaming.core.catalog.UploadIntentStore.UploadIntent;

/** Local reservation only. A later milestone provisions Media after this transaction commits. */
@Service
public class UploadIntentService {
    private final IdentityAccess access;
    private final CatalogStore catalog;
    private final UploadIntentStore intents;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    UploadIntentService(IdentityAccess access, CatalogStore catalog, UploadIntentStore intents,
            JdbcTemplate jdbc, Clock clock) {
        this.access = access;
        this.catalog = catalog;
        this.intents = intents;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public Reservation reserve(IdentityPrincipal actor, UUID contentId, UUID requestId,
            long expectedVersion, long byteLength, String sha256) {
        var current = access.lockCurrent(actor, true);
        if (contentId == null || requestId == null || expectedVersion <= 0 || expectedVersion == Long.MAX_VALUE
                || byteLength < 1 || byteLength > 268435456 || sha256 == null
                || !sha256.matches("[0-9a-f]{64}")) {
            throw DomainException.invalid();
        }
        String fingerprint = fingerprint(contentId, expectedVersion, byteLength, sha256);
        var existing = intents.findByRequest(current.accountId(), requestId);
        if (existing != null) {
            if (!existing.fingerprint().equals(fingerprint)) { throw DomainException.conflict("IDEMPOTENCY_CONFLICT"); }
            var content = catalog.lock(existing.contentId());
            if (!existing.bindingId().equals(content.candidateBinding())) {
                throw DomainException.conflict("UPLOAD_STATE_CONFLICT");
            }
            return view(existing);
        }

        var content = catalog.lock(contentId);
        if (content.version() != expectedVersion) { throw DomainException.conflict("VERSION_CONFLICT"); }
        if (!content.kind().playable() || content.publishedRevision() != null || content.activeBinding() != null
                || content.candidateBinding() != null) { throw DomainException.conflict("UPLOAD_NOT_ALLOWED"); }
        UUID bindingId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        UUID uploadId = UUID.randomUUID();
        var now = clock.instant();
        jdbc.update("INSERT INTO catalog_media_bindings(id, content_id, asset_id, asset_version) VALUES (?, ?, ?, 1)",
                bindingId, contentId, assetId);
        jdbc.update("UPDATE catalog_contents SET candidate_binding = ?, version = version + 1 WHERE id = ?",
                bindingId, contentId);
        var intent = new UploadIntent(uploadId, current.accountId(), requestId, contentId, bindingId,
                assetId, 1, expectedVersion, expectedVersion + 1, byteLength, sha256,
                fingerprint, now, now.plus(Duration.ofHours(1)));
        intents.insert(intent);
        return view(intents.owned(current.accountId(), uploadId));
    }

    @Transactional
    public Reservation read(IdentityPrincipal actor, UUID uploadId) {
        var current = access.lockCurrent(actor, true);
        if (uploadId == null) { throw DomainException.invalid(); }
        return view(intents.owned(current.accountId(), uploadId));
    }

    private static Reservation view(UploadIntent intent) {
        return new Reservation(intent.id(), intent.requestId(), intent.contentId(), intent.bindingId(),
                intent.assetId(), intent.assetVersion(), intent.catalogVersionAtReservation(),
                intent.byteLength(), intent.sha256(), intent.createdAt(), intent.expiresAt());
    }

    private static String fingerprint(UUID contentId, long version, long byteLength, String sha256) {
        try {
            byte[] canonical = (contentId + "\n" + version + "\n" + byteLength + "\n" + sha256)
                    .getBytes(StandardCharsets.US_ASCII);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public record Reservation(UUID uploadId, UUID requestId, UUID contentId, UUID bindingId,
            UUID assetId, long assetVersion, long catalogVersionAtReservation, long byteLength,
            String sha256, java.time.Instant createdAt, java.time.Instant expiresAt) {}
}
