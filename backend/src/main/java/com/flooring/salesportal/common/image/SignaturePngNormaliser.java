package com.flooring.salesportal.common.image;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * The public signing surfaces' PNG content rules, shared by the public quote accept (Phase 16F PR1) and
 * the public invoice accept (Phase 16G PR 4; 16G contract §9.4 "the public quote accept's, unchanged").
 * Moved verbatim from {@code QuoteAcceptanceService}; pure functions only - the callers keep their own
 * multipart part rules, required-signature and declared-type checks and error codes.
 *
 * <p>{@link #normalise} accepts the uploaded bytes only when: they are non-empty and at most 2,097,152
 * bytes; they start with the 8-byte PNG signature; the first chunk is a 13-byte {@code IHDR} which,
 * checked BEFORE any pixel is decoded, declares positive dimensions of at most 8,192 px per side and
 * 4,000,000 px in total, a bit depth of at most 8 and a valid PNG colour type (0, 2, 3, 4 or 6); and the
 * JDK PNG reader decodes them within those bounds. It then returns the SERVER-NORMALISED signature: the
 * decoded pixels re-encoded as a clean PNG. Only these bytes may be stored and embedded in a signed PDF
 * - ancillary chunks (iCCP / zTXt / iTXt / tEXt ...), bytes after IEND and over-long IDAT streams never
 * reach storage or PDFBox, so a compressed payload the bounded decode does not inflate cannot be inflated
 * later by the PDF render (decompression bomb). The image itself (dimensions + pixels) is unchanged.
 */
public final class SignaturePngNormaliser {

    // Exactly 2 MB: more -> rejected; exactly this many bytes is allowed (Phase 13 D.8 parity).
    private static final long MAX_SIGNATURE_SIZE_BYTES = 2_097_152L;
    // Safe-decode bounds, checked from the IHDR header BEFORE any pixel is decoded, so a tiny compressed
    // file declaring enormous dimensions or 16-bit samples (decompression bomb) is rejected instead of
    // exhausting memory on an unauthenticated surface. A browser canvas export (canvas.toBlob('image/png'))
    // is always 8-bit, and a typical DPR-scaled signature pad stays well below these bounds; at most
    // 4,000,000 8-bit pixels keeps one decoded RGBA raster at or below 16 MiB. An uncapped canvas at
    // extreme browser zoom CAN exceed 4,000,000 px, so the public signing pads cap their export size to
    // these bounds.
    private static final int MAX_SIGNATURE_SIDE_PX = 8_192;
    private static final long MAX_SIGNATURE_PIXELS = 4_000_000L;
    private static final int MAX_SIGNATURE_BIT_DEPTH = 8;
    private static final byte[] PNG_MAGIC = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    // IHDR: 8-byte magic, then the 4-byte length (13) + "IHDR" + width(4) height(4) bitDepth(1)
    // colourType(1) compression(1) filter(1) interlace(1) + CRC(4).
    private static final int IHDR_LENGTH_OFFSET = 8;
    private static final int IHDR_TYPE_OFFSET = 12;
    private static final int IHDR_WIDTH_OFFSET = 16;
    private static final int IHDR_HEIGHT_OFFSET = 20;
    private static final int IHDR_BIT_DEPTH_OFFSET = 24;
    private static final int IHDR_COLOUR_TYPE_OFFSET = 25;
    private static final int IHDR_MIN_FILE_LENGTH = 33;

    private SignaturePngNormaliser() {
    }

    /**
     * The uploaded signature bytes re-encoded as a clean PNG, or null when they break any rule in the
     * class javadoc (empty, over 2,097,152 bytes, wrong magic, an IHDR outside the safe-decode bounds, or
     * not decodable). The callers map null to their own 400 {@code SIGNATURE_INVALID}.
     */
    public static byte[] normalise(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_SIGNATURE_SIZE_BYTES
                || !hasPngMagic(bytes) || !hasSafePngHeader(bytes)) {
            return null;
        }
        return decodeAndReencodePng(bytes);
    }

    private static boolean hasPngMagic(byte[] bytes) {
        if (bytes.length < PNG_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < PNG_MAGIC.length; i++) {
            if (bytes[i] != PNG_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * The IHDR pre-check, from the raw bytes BEFORE any decoder runs: the first chunk must be a
     * 13-byte {@code IHDR}; width/height within the bounds (each side at most 8,192 px, at most
     * 4,000,000 px); bit depth at most 8 (a canvas export is 8-bit; 16-bit samples would double the
     * decoded raster); a valid PNG colour type (0, 2, 3, 4 or 6).
     */
    private static boolean hasSafePngHeader(byte[] bytes) {
        if (bytes.length < IHDR_MIN_FILE_LENGTH) {
            return false;
        }
        if (readInt(bytes, IHDR_LENGTH_OFFSET) != 13
                || bytes[IHDR_TYPE_OFFSET] != 'I' || bytes[IHDR_TYPE_OFFSET + 1] != 'H'
                || bytes[IHDR_TYPE_OFFSET + 2] != 'D' || bytes[IHDR_TYPE_OFFSET + 3] != 'R') {
            return false;
        }
        long width = readInt(bytes, IHDR_WIDTH_OFFSET) & 0xFFFFFFFFL;
        long height = readInt(bytes, IHDR_HEIGHT_OFFSET) & 0xFFFFFFFFL;
        int bitDepth = bytes[IHDR_BIT_DEPTH_OFFSET] & 0xFF;
        int colourType = bytes[IHDR_COLOUR_TYPE_OFFSET] & 0xFF;
        if (width <= 0 || height <= 0
                || width > MAX_SIGNATURE_SIDE_PX || height > MAX_SIGNATURE_SIDE_PX
                || width * height > MAX_SIGNATURE_PIXELS) {
            return false;
        }
        if (bitDepth > MAX_SIGNATURE_BIT_DEPTH) {
            return false;
        }
        return colourType == 0 || colourType == 2 || colourType == 3 || colourType == 4 || colourType == 6;
    }

    private static int readInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24)
                | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8)
                | (bytes[offset + 3] & 0xFF);
    }

    /**
     * Decode the (header-checked) PNG with the JDK PNG reader — metadata ignored, dimensions re-checked
     * from the reader — and RE-ENCODE the pixels as a clean 8-bit ARGB PNG. Returns the re-encoded bytes,
     * or null when the stream is not a decodable PNG within the bounds. Recoverable decoder failures
     * (I/O, malformed data, runtime exceptions from corrupt input) are rejections; a JVM
     * {@link Error} is not swallowed.
     */
    private static byte[] decodeAndReencodePng(byte[] bytes) {
        // In-memory stream: no ImageIO disk-cache temp file per attempt.
        try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            BufferedImage decoded;
            try {
                if (!"png".equalsIgnoreCase(reader.getFormatName())) {
                    return null;
                }
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0
                        || width > MAX_SIGNATURE_SIDE_PX || height > MAX_SIGNATURE_SIDE_PX
                        || (long) width * height > MAX_SIGNATURE_PIXELS) {
                    return null;
                }
                decoded = reader.read(0);
            } finally {
                reader.dispose();
            }
            if (decoded == null) {
                return null;
            }
            // Canonical sRGB ARGB copy (transparency kept), then a fresh PNG encode: only IHDR / IDAT /
            // IEND (+ standard chunks the writer emits) — none of the uploaded ancillary data survives.
            BufferedImage argb = new BufferedImage(decoded.getWidth(), decoded.getHeight(),
                    BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D graphics = argb.createGraphics();
            try {
                // Src (not the default SrcOver): an exact per-pixel copy/convert with NO blending, so
                // semi-transparent (anti-aliased) stroke pixels keep their exact colour + alpha.
                graphics.setComposite(java.awt.AlphaComposite.Src);
                graphics.drawImage(decoded, 0, 0, null);
            } finally {
                graphics.dispose();
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            if (!ImageIO.write(argb, "png", out)) {
                return null;
            }
            byte[] reencoded = out.toByteArray();
            return reencoded.length == 0 ? null : reencoded;
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }
}
