package br.com.sergio.sapiensia.product;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;

/** Builds images in memory for the tests; nothing is ever read from a committed file. */
public final class TestImages {

    private static final String WEBP_LOSSLESS_1X1 = "UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA==";

    private TestImages() {}

    public static byte[] png(int width, int height) {
        return encode(width, height, "png");
    }

    public static byte[] jpeg(int width, int height) {
        return encode(width, height, "jpg");
    }

    private static byte[] encode(int width, int height, String format) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                image.setRGB(x, y, (x * 31 + y * 17) & 0xFFFFFF);
            }
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, format, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A valid lossless 1x1 WebP (a RIFF with a single VP8L chunk). */
    public static byte[] webpLossless1x1() {
        return Base64.getDecoder().decode(WEBP_LOSSLESS_1X1);
    }

    // ---- PNG ----

    /** Inserts a chunk with a correct CRC right before IEND. */
    public static byte[] withPngChunk(byte[] png, String type, byte[] data) {
        int iend = png.length - 12;
        byte[] chunk = pngChunk(type, data);
        return concat(Arrays.copyOfRange(png, 0, iend), chunk, Arrays.copyOfRange(png, iend, png.length));
    }

    public static byte[] pngChunk(String type, byte[] data) {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        return concat(be32(data.length), typeBytes, data, be32(crc.getValue()));
    }

    /** A PNG with an IHDR that claims the given size, one tiny IDAT and IEND: nothing like enough data to decode. */
    public static byte[] pngHeaderOnly(int width, int height) {
        byte[] ihdr = concat(be32(width), be32(height), new byte[] {8, 2, 0, 0, 0});
        return concat(
                new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A},
                pngChunk("IHDR", ihdr),
                pngChunk("IDAT", new byte[] {0x78, 0x01}),
                pngChunk("IEND", new byte[0]));
    }

    // ---- JPEG ----

    /** Inserts a segment (marker {@code FF marker}, length, data) right after SOI. */
    public static byte[] withJpegSegment(byte[] jpeg, int marker, byte[] data) {
        byte[] segment = concat(new byte[] {(byte) 0xFF, (byte) marker}, be16(data.length + 2), data);
        return concat(Arrays.copyOfRange(jpeg, 0, 2), segment, Arrays.copyOfRange(jpeg, 2, jpeg.length));
    }

    // ---- WebP ----

    public static byte[] webpChunk(String type, byte[] data) {
        byte[] padding = data.length % 2 == 0 ? new byte[0] : new byte[1];
        return concat(type.getBytes(StandardCharsets.US_ASCII), le32(data.length), data, padding);
    }

    /** A RIFF/WEBP container around the given chunks, with a correct RIFF size. */
    public static byte[] webpRiff(byte[]... chunks) {
        byte[] body = concat(chunks);
        return concat(
                "RIFF".getBytes(StandardCharsets.US_ASCII),
                le32(body.length + 4),
                "WEBP".getBytes(StandardCharsets.US_ASCII),
                body);
    }

    /** VP8X flags: 0x08 EXIF, 0x02 animation. Canvas is 1x1. */
    public static byte[] vp8xChunk(int flags) {
        return webpChunk("VP8X", new byte[] {(byte) flags, 0, 0, 0, 0, 0, 0, 0, 0, 0});
    }

    /** The VP8L chunk (header included) of a RIFF produced by {@link #webpLossless1x1()}. */
    public static byte[] vp8lChunk(byte[] webp) {
        return Arrays.copyOfRange(webp, 12, webp.length);
    }

    /** Wraps the VP8L image in a VP8X container, optionally with an EXIF chunk and the animation flag. */
    public static byte[] webpVp8xWith(byte[] vp8l, boolean exif, boolean animated) {
        int flags = (exif ? 0x08 : 0) | (animated ? 0x02 : 0);
        byte[] exifChunk = exif
                ? webpChunk("EXIF", "Exif\0\0GPS-looking-data".getBytes(StandardCharsets.ISO_8859_1))
                : new byte[0];
        return webpRiff(vp8xChunk(flags), vp8lChunk(vp8l), exifChunk);
    }

    // ---- bytes ----

    public static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    public static byte[] be16(int value) {
        return new byte[] {(byte) (value >> 8), (byte) value};
    }

    public static byte[] be32(long value) {
        return new byte[] {(byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) value};
    }

    public static byte[] le32(long value) {
        return new byte[] {(byte) value, (byte) (value >> 8), (byte) (value >> 16), (byte) (value >> 24)};
    }
}
