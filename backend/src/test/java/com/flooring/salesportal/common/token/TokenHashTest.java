package com.flooring.salesportal.common.token;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

/**
 * DB-free unit test for {@link TokenHash} (Phase 16G PR 3, 16G contract §7.3): the one shared token hash
 * that replaced the two private quote copies ({@code QuoteSendService.hashToken} and
 * {@code PublicQuoteService.sha256Hex}) with unchanged behaviour - SHA-256 of the UTF-8 bytes as 64
 * lowercase hex characters. The quote send / public / acceptance suites prove the stored-hash round
 * trip end to end; this pins the derivation itself.
 */
class TokenHashTest {

    @Test
    void knownVectors_lowercaseHex() {
        // FIPS 180-2 / NIST test vectors.
        Assertions.assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                TokenHash.sha256Hex("abc"));
        Assertions.assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                TokenHash.sha256Hex(""));
    }

    @Test
    void mintedShapedToken_hashesToTheDigestOfItsUtf8Bytes_as64LowercaseHexChars() throws Exception {
        // The shape every send mints: 32 bytes, URL-safe Base64 without padding (43 characters).
        byte[] raw = new byte[32];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) (i * 37 + 11);
        }
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Assertions.assertEquals(43, token.length());

        String expected = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        String hash = TokenHash.sha256Hex(token);
        Assertions.assertEquals(expected, hash);
        Assertions.assertTrue(hash.matches("[0-9a-f]{64}"), "64 lowercase hex characters: " + hash);
        Assertions.assertEquals(hash, TokenHash.sha256Hex(token), "deterministic");
    }

    @Test
    void nonAsciiInput_isHashedAsUtf8() throws Exception {
        String value = "çé€";
        String expected = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        Assertions.assertEquals(expected, TokenHash.sha256Hex(value));
        Assertions.assertNotEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.ISO_8859_1))), TokenHash.sha256Hex(value));
    }
}
