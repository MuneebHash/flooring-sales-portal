package com.flooring.salesportal.common.image;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.WritableRaster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Unit tests of the PNG rules shared by the public quote accept and (16G PR 4) the public invoice accept
 * ({@link SignaturePngNormaliser}, moved unchanged from {@code QuoteAcceptanceService}): the INCLUSIVE edges of
 * every bound are accepted and the first value past each edge is refused, every valid PNG colour type
 * (0, 2, 3, 4, 6) is accepted, and the result is a clean re-encode with the same dimensions and pixels. The two
 * endpoint suites cover the same rules through HTTP; this class pins the edges a {@code >} vs {@code >=} slip
 * would move.
 */
class SignaturePngNormaliserTest {

    private static final int MAX_SIDE = 8_192;
    private static final int MAX_BYTES = 2_097_152;
    private static final int IHDR_BIT_DEPTH_OFFSET = 24;
    private static final int IHDR_COLOUR_TYPE_OFFSET = 25;

    @Test
    void sidesOfExactly8192Pixels_areAccepted_8193IsRefused() throws IOException {
        assertAcceptedSameImage(binaryPng(MAX_SIDE, 1), "8,192 x 1");
        assertAcceptedSameImage(binaryPng(1, MAX_SIDE), "1 x 8,192");
        Assertions.assertNull(SignaturePngNormaliser.normalise(binaryPng(MAX_SIDE + 1, 1)), "8,193 x 1 is refused");
        Assertions.assertNull(SignaturePngNormaliser.normalise(binaryPng(1, MAX_SIDE + 1)), "1 x 8,193 is refused");
    }

    @Test
    void exactly4000000Pixels_isAccepted_onePixelRowMoreIsRefused() throws IOException {
        assertAcceptedSameImage(binaryPng(2_000, 2_000), "2,000 x 2,000 = exactly 4,000,000 px");
        Assertions.assertNull(SignaturePngNormaliser.normalise(binaryPng(2_000, 2_001)),
                "2,000 x 2,001 = 4,002,000 px is refused");
    }

    @Test
    void everyValidColourType_0_2_3_4_6_isAccepted_andReencodedWithTheSamePixels() throws IOException {
        assertColourTypeAccepted(gray(7, 5), 0);
        assertColourTypeAccepted(rgb(7, 5), 2);
        assertColourTypeAccepted(indexed(7, 5), 3);
        assertColourTypeAccepted(grayAlpha(7, 5), 4);
        assertColourTypeAccepted(argb(7, 5), 6);
    }

    @Test
    void invalidColourTypeOrSixteenBitSamples_areRefusedBeforeAnyDecode() throws IOException {
        byte[] colourTypeOne = png(argb(4, 3));
        colourTypeOne[IHDR_COLOUR_TYPE_OFFSET] = 1;
        Assertions.assertNull(SignaturePngNormaliser.normalise(colourTypeOne), "colour type 1 does not exist");
        byte[] colourTypeFive = png(argb(4, 3));
        colourTypeFive[IHDR_COLOUR_TYPE_OFFSET] = 5;
        Assertions.assertNull(SignaturePngNormaliser.normalise(colourTypeFive), "colour type 5 does not exist");

        BufferedImage sixteenBit = new BufferedImage(4, 3, BufferedImage.TYPE_USHORT_GRAY);
        byte[] sixteen = png(sixteenBit);
        Assertions.assertEquals(16, sixteen[IHDR_BIT_DEPTH_OFFSET], "setup: a real 16-bit PNG");
        Assertions.assertNull(SignaturePngNormaliser.normalise(sixteen), "bit depth 16 is refused");
    }

    @Test
    void exactly2097152Bytes_isAccepted_oneByteMoreIsRefused_andTrailingBytesNeverSurvive() throws IOException {
        byte[] small = png(argb(6, 3));
        byte[] atLimit = Arrays.copyOf(small, MAX_BYTES);
        byte[] normalised = SignaturePngNormaliser.normalise(atLimit);
        Assertions.assertNotNull(normalised, "exactly 2,097,152 bytes (a PNG zero-padded after IEND) is accepted");
        Assertions.assertTrue(normalised.length < MAX_BYTES, "the padding after IEND is dropped");
        assertSamePixels(ImageIO.read(new ByteArrayInputStream(small)), normalised, "the padded upload");

        Assertions.assertNull(SignaturePngNormaliser.normalise(Arrays.copyOf(small, MAX_BYTES + 1)),
                "2,097,153 bytes is refused");
    }

    @Test
    void emptyNullAndNonPngBytes_areRefused() throws IOException {
        Assertions.assertNull(SignaturePngNormaliser.normalise(null), "null");
        Assertions.assertNull(SignaturePngNormaliser.normalise(new byte[0]), "empty");
        Assertions.assertNull(SignaturePngNormaliser.normalise("GIF89a not a png".getBytes(StandardCharsets.US_ASCII)),
                "wrong magic");
        byte[] valid = png(argb(3, 3));
        Assertions.assertNull(SignaturePngNormaliser.normalise(Arrays.copyOf(valid, 20)), "a truncated IHDR");
        byte[] corrupt = valid.clone();
        // Keep the 8-byte magic and the 33-byte IHDR; garble everything after them (the IDAT stream).
        for (int i = 33; i < corrupt.length; i++) {
            corrupt[i] = (byte) 0x5A;
        }
        Assertions.assertNull(SignaturePngNormaliser.normalise(corrupt), "an undecodable stream after a valid IHDR");
    }

    @Test
    void ancillaryChunks_neverSurviveTheReencode() throws IOException {
        byte[] withText = withTextChunk(png(argb(5, 4)), "Comment", "uploaded ancillary data");
        Assertions.assertTrue(contains(withText, "tEXt"), "setup: the upload carries a tEXt chunk");
        byte[] normalised = SignaturePngNormaliser.normalise(withText);
        Assertions.assertNotNull(normalised, "a valid PNG with an ancillary chunk is accepted");
        Assertions.assertFalse(contains(normalised, "tEXt"), "the ancillary chunk is dropped");
        Assertions.assertFalse(contains(normalised, "uploaded ancillary data"), "its content is dropped");
    }

    // ---- helpers ----

    private static void assertColourTypeAccepted(BufferedImage image, int colourType) throws IOException {
        byte[] bytes = png(image);
        Assertions.assertEquals(colourType, bytes[IHDR_COLOUR_TYPE_OFFSET] & 0xFF,
                "setup: ImageIO wrote colour type " + colourType);
        byte[] normalised = SignaturePngNormaliser.normalise(bytes);
        Assertions.assertNotNull(normalised, "colour type " + colourType + " is accepted");
        assertSamePixels(ImageIO.read(new ByteArrayInputStream(bytes)), normalised, "colour type " + colourType);
    }

    private static void assertAcceptedSameImage(byte[] bytes, String label) throws IOException {
        byte[] normalised = SignaturePngNormaliser.normalise(bytes);
        Assertions.assertNotNull(normalised, label + " is accepted");
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(normalised));
        BufferedImage original = ImageIO.read(new ByteArrayInputStream(bytes));
        Assertions.assertEquals(original.getWidth(), decoded.getWidth(), label + ": width kept");
        Assertions.assertEquals(original.getHeight(), decoded.getHeight(), label + ": height kept");
    }

    private static void assertSamePixels(BufferedImage original, byte[] normalised, String label) throws IOException {
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(normalised));
        Assertions.assertEquals(original.getWidth(), decoded.getWidth(), label + ": width kept");
        Assertions.assertEquals(original.getHeight(), decoded.getHeight(), label + ": height kept");
        for (int y = 0; y < original.getHeight(); y++) {
            for (int x = 0; x < original.getWidth(); x++) {
                Assertions.assertEquals(original.getRGB(x, y), decoded.getRGB(x, y),
                        label + ": pixel (" + x + "," + y + ") kept");
            }
        }
    }

    private static byte[] binaryPng(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY);
        image.setRGB(0, 0, 0xFFFFFFFF);
        return png(image);
    }

    /**
     * Black and white samples only: the JDK reads a grey PNG into a linear CS_GRAY image whose
     * {@code getRGB} applies a gamma curve, while the ARGB re-encode copies the sample itself; 0 and 255 are
     * that curve's fixed points, so the pixel comparison holds on either path (a pen stroke on a pad).
     */
    private static BufferedImage gray(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        WritableRaster raster = image.getRaster();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                raster.setSample(x, y, 0, ((x + y) % 3 == 0) ? 0 : 255);
            }
        }
        return image;
    }

    private static BufferedImage rgb(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        paint(image);
        return image;
    }

    private static BufferedImage indexed(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_INDEXED);
        paint(image);
        return image;
    }

    private static BufferedImage grayAlpha(int width, int height) {
        ColorModel model = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_GRAY), true, false,
                Transparency.TRANSLUCENT, DataBuffer.TYPE_BYTE);
        WritableRaster raster = model.createCompatibleWritableRaster(width, height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // Black or white (see gray) with a varied alpha: the anti-aliased edge of a stroke.
                raster.setPixel(x, y, new int[] {((x + y) % 2 == 0) ? 0 : 255, (x * 53 + y * 29 + 40) & 0xFF});
            }
        }
        return new BufferedImage(model, raster, false, null);
    }

    private static BufferedImage argb(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // Semi-transparent, anti-aliased-looking stroke pixels: the alpha must survive exactly.
                int alpha = (x * 41 + y * 17) & 0xFF;
                image.setRGB(x, y, (alpha << 24) | ((x * 23) & 0xFF) << 16 | ((y * 61) & 0xFF) << 8 | 0x33);
            }
        }
        return image;
    }

    private static void paint(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, 0xFF000000 | ((x * 50) & 0xFF) << 16 | ((y * 40) & 0xFF) << 8 | ((x + y) * 20 & 0xFF));
            }
        }
    }

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Assertions.assertTrue(ImageIO.write(image, "png", out), "setup: ImageIO wrote a PNG");
        return out.toByteArray();
    }

    /** Insert a tEXt chunk right after the IHDR (offset 33); its CRC need not be valid for the reader to skip it. */
    private static byte[] withTextChunk(byte[] png, String keyword, String text) {
        byte[] data = (keyword + "\0" + text).getBytes(StandardCharsets.ISO_8859_1);
        byte[] chunk = new byte[12 + data.length];
        chunk[0] = (byte) (data.length >>> 24);
        chunk[1] = (byte) (data.length >>> 16);
        chunk[2] = (byte) (data.length >>> 8);
        chunk[3] = (byte) data.length;
        System.arraycopy("tEXt".getBytes(StandardCharsets.US_ASCII), 0, chunk, 4, 4);
        System.arraycopy(data, 0, chunk, 8, data.length);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(chunk, 4, 4 + data.length);
        long value = crc.getValue();
        chunk[8 + data.length] = (byte) (value >>> 24);
        chunk[9 + data.length] = (byte) (value >>> 16);
        chunk[10 + data.length] = (byte) (value >>> 8);
        chunk[11 + data.length] = (byte) value;
        byte[] result = new byte[png.length + chunk.length];
        System.arraycopy(png, 0, result, 0, 33);
        System.arraycopy(chunk, 0, result, 33, chunk.length);
        System.arraycopy(png, 33, result, 33 + chunk.length, png.length - 33);
        return result;
    }

    private static boolean contains(byte[] haystack, String needle) {
        byte[] target = needle.getBytes(StandardCharsets.ISO_8859_1);
        outer:
        for (int i = 0; i <= haystack.length - target.length; i++) {
            for (int j = 0; j < target.length; j++) {
                if (haystack[i + j] != target[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
