package com.libra.streaming.core.identity;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class IdentitySecrets {
    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec encryptionKey;
    private final SecretKeySpec fingerprintKey;

    public IdentitySecrets(IdentityProperties properties) {
        encryptionKey = new SecretKeySpec(Base64.getDecoder().decode(properties.mailKey()), "AES");
        fingerprintKey = new SecretKeySpec(Base64.getDecoder().decode(properties.jwtKey()), "HmacSHA256");
    }

    public String token() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public String fingerprint(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(fingerprintKey);
            return HexFormat.of().formatHex(mac.doFinal(("rate-limit:" + value).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC unavailable", exception);
        }
    }

    public String encrypt(UUID messageId, String plaintext) {
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        try {
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, messageId, nonce);
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(java.nio.ByteBuffer.allocate(nonce.length + encrypted.length)
                    .put(nonce).put(encrypted).array());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Mail encryption failed", exception);
        }
    }

    public String decrypt(UUID messageId, String encoded) {
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length < 28) { throw new IllegalArgumentException("Invalid encrypted mail"); }
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, messageId, java.util.Arrays.copyOf(bytes, 12));
            return new String(cipher.doFinal(bytes, 12, bytes.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("Mail authentication failed");
        }
    }

    private Cipher cipher(int mode, UUID messageId, byte[] nonce) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, encryptionKey, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(messageId.toString().getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
}
