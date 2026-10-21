package com.flooring.salesportal.common.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The one shared hash of a public link token (Phase 16G PR 3, 16G contract §7.3): SHA-256 of the
 * plaintext token's UTF-8 bytes, as lowercase hex. It is the only token material ever stored
 * ({@code token_hash}) and the lookup key for a presented token. Used by the quote send (minting) and
 * the public quote surface (resolution); the invoice link uses it from PR 4.
 *
 * <p>Hashing only: minting, the shape gate, the lookup and the constant-time comparison stay with the
 * callers. The plaintext is never logged or stored here.
 */
public final class TokenHash {

    private TokenHash() {
    }

    /** SHA-256 of {@code plainToken} (UTF-8), 64 lowercase hex characters. */
    public static String sha256Hex(String plainToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(plainToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
