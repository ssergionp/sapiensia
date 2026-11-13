package br.com.sergio.sapiensia.product;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Checks an uploaded cover image from its bytes alone and returns a copy without metadata (SEC-043, SEC-047).
 *
 * <p>The image is never decoded: the file (at most 2 MB) is walked chunk by chunk (PNG, WebP) or segment by segment
 * (JPEG) with every declared length checked against the bytes that are really there, and only the parts needed to
 * show the image are copied. Anything malformed, truncated or lying about its lengths is refused
 * ({@code COVER_INVALID}), never an exception. The file name and the declared content type play no part.
 */
@Component
public class CoverImageValidator {

    public static final long MAX_BYTES = 2_097_152L;
    public static final int MAX_SIDE = 4000;

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JPEG_SIGNATURE = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] ICC_PROFILE = "ICC_PROFILE".getBytes(StandardCharsets.US_ASCII);
    // Ancillary PNG chunks needed to display the image; the rest (tEXt, zTXt, iTXt, eXIf, tIME...) is dropped.
    private static final Set<String> PNG_KEPT = Set.of(
            "IHDR", "PLTE", "IDAT", "IEND", "tRNS", "gAMA", "cHRM", "sRGB", "iCCP", "sBIT", "bKGD", "pHYs", "cICP");
    private static final Set<String> PNG_CRITICAL = Set.of("IHDR", "PLTE", "IDAT", "IEND");
    private static final Set<String> WEBP_KEPT = Set.of("ALPH", "ICCP");

    /** Carries a rejection out of the walking code; no stack trace, it is only control flow. */
    private static final class Rejection extends RuntimeException {
        private final transient ProductCoverRejection reason;

        Rejection(ProductCoverRejection reason) {
            super(null, null, false, false);
            this.reason = reason;
        }
    }

    public CoverCheck check(Path file) {
        byte[] bytes;
        try {
            // The size is checked before reading, so an oversized upload is never loaded into memory.
            if (Files.size(file) > MAX_BYTES) {
                return reject(ProductCoverRejection.COVER_TOO_LARGE);
            }
            bytes = Files.readAllBytes(file);
        } catch (IOException | RuntimeException e) {
            return reject(ProductCoverRejection.COVER_INVALID);
        }
        if (bytes.length > MAX_BYTES) {
            return reject(ProductCoverRejection.COVER_TOO_LARGE);
        }
        if (bytes.length == 0) {
            return reject(ProductCoverRejection.COVER_EMPTY);
        }
        try {
            return parse(bytes);
        } catch (RuntimeException e) {
            // Last line of defense: whatever the walking code did not foresee is a refusal, never a 500.
            return reject(ProductCoverRejection.COVER_INVALID);
        }
    }

    /**
     * The walking code without the last-line catch-all: only designed rejections become a result, anything else
     * (a bug such as an out-of-bounds index) propagates. The tests fuzz this entry point so such bugs stay visible.
     */
    static CoverCheck parse(byte[] bytes) {
        try {
            if (startsWith(bytes, PNG_SIGNATURE)) {
                return png(bytes);
            }
            if (startsWith(bytes, JPEG_SIGNATURE)) {
                return jpeg(bytes);
            }
            if (bytes.length >= 12
                    && ascii(bytes, 0, 4).equals("RIFF")
                    && ascii(bytes, 8, 4).equals("WEBP")) {
                return webp(bytes);
            }
            return reject(ProductCoverRejection.COVER_NOT_IMAGE);
        } catch (Rejection e) {
            return reject(e.reason);
        }
    }

    // ---- PNG ----

    private static CoverCheck png(byte[] b) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(b.length);
        out.write(b, 0, PNG_SIGNATURE.length);
        int pos = PNG_SIGNATURE.length;
        boolean first = true;
        boolean hasData = false;
        int width = 0;
        int height = 0;
        while (true) {
            need(b, pos, 8);
            long length = u32be(b, pos);
            String type = ascii(b, pos + 4, 4);
            // length + type + data + CRC
            long end = pos + 12L + length;
            if (end > b.length) {
                throw invalid();
            }
            if (type.equals("acTL")) {
                throw new Rejection(ProductCoverRejection.COVER_ANIMATED);
            }
            if (first != type.equals("IHDR")) {
                throw invalid();
            }
            boolean critical = (b[pos + 4] & 0x20) == 0;
            if (critical && !PNG_CRITICAL.contains(type)) {
                throw invalid();
            }
            if (first) {
                if (length != 13) {
                    throw invalid();
                }
                long w = u32be(b, pos + 8);
                long h = u32be(b, pos + 12);
                checkSide(w, h);
                width = (int) w;
                height = (int) h;
                first = false;
            }
            if (type.equals("IDAT")) {
                hasData = true;
            }
            if (PNG_KEPT.contains(type)) {
                out.write(b, pos, (int) (end - pos));
            }
            if (type.equals("IEND")) {
                if (length != 0 || !hasData) {
                    throw invalid();
                }
                // Anything after IEND (a polyglot tail) is not copied.
                return new CoverCheck.Valid(CoverFormat.PNG, width, height, out.toByteArray());
            }
            pos = (int) end;
        }
    }

    // ---- JPEG ----

    private static CoverCheck jpeg(byte[] b) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(b.length);
        out.write(0xFF);
        out.write(0xD8);
        int pos = 2;
        boolean hasFrame = false;
        int width = 0;
        int height = 0;
        while (true) {
            need(b, pos, 2);
            if (u8(b, pos) != 0xFF) {
                throw invalid();
            }
            int marker = u8(b, pos + 1);
            if (marker == 0xFF) {
                pos++; // fill byte
                continue;
            }
            // Standalone markers (no length) cannot appear before the first scan.
            if (marker == 0x00 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD9)) {
                throw invalid();
            }
            int end = segmentEnd(b, pos);
            if (isFrameHeader(marker)) {
                if (hasFrame || end - pos < 10) {
                    throw invalid();
                }
                long h = u16(b, pos + 5);
                long w = u16(b, pos + 7);
                checkSide(w, h);
                width = (int) w;
                height = (int) h;
                hasFrame = true;
            }
            if (marker == 0xDA) {
                if (!hasFrame) {
                    throw invalid();
                }
                copyScansUpToEoi(b, pos, end, out);
                return new CoverCheck.Valid(CoverFormat.JPEG, width, height, out.toByteArray());
            }
            if (keepJpegSegment(b, marker, pos + 4, end)) {
                out.write(b, pos, end - pos);
            }
            pos = end;
        }
    }

    /**
     * From the first SOS to the first EOI: entropy-coded bytes (including FF00 stuffing and RSTn) are copied as
     * they are and only scanned to find the next real marker (FF followed by something other than 00, RSTn or FF).
     * Segments
     * between scans are stepped over by their declared length, never by searching inside them, and filtered like the
     * ones before the scan. Everything after the first EOI is dropped.
     */
    private static void copyScansUpToEoi(byte[] b, int scanStart, int headerEnd, ByteArrayOutputStream out) {
        out.write(b, scanStart, headerEnd - scanStart);
        int p = headerEnd;
        int copyFrom = headerEnd; // start of the entropy-coded bytes not yet copied
        while (p < b.length) {
            if (u8(b, p) != 0xFF) {
                p++;
                continue;
            }
            need(b, p, 2);
            int marker = u8(b, p + 1);
            if (marker == 0x00 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                p += 2;
            } else if (marker == 0xFF) {
                p++;
            } else if (marker == 0xD9) {
                out.write(b, copyFrom, p + 2 - copyFrom);
                return;
            } else {
                // A real marker between scans: the same keep/drop rule as before the first scan, so EXIF, XMP or a
                // comment hidden between progressive scans does not survive. SOS headers are kept (their data follows).
                out.write(b, copyFrom, p - copyFrom);
                int end = segmentEnd(b, p);
                if (marker == 0xDA || keepJpegSegment(b, marker, p + 4, end)) {
                    out.write(b, p, end - p);
                }
                p = end;
                copyFrom = end;
            }
        }
        throw invalid(); // no EOI
    }

    /** End (exclusive) of the segment whose marker starts at {@code pos}, after checking its declared length. */
    private static int segmentEnd(byte[] b, int pos) {
        need(b, pos + 2, 2);
        int length = u16(b, pos + 2);
        if (length < 2 || pos + 2L + length > b.length) {
            throw invalid();
        }
        return pos + 2 + length;
    }

    private static boolean isFrameHeader(int marker) {
        return marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
    }

    private static boolean keepJpegSegment(byte[] b, int marker, int dataStart, int end) {
        if (marker == 0xE2) {
            // Only color profiles; other APP2 data (FlashPix, MPF...) is not needed to show the image.
            return dataStart + ICC_PROFILE.length <= end && regionMatches(b, dataStart, ICC_PROFILE);
        }
        // APP1 (EXIF, XMP), APP3-APP13 (IPTC in APP13), APP15 and COM go away; APP0 (JFIF) and APP14 (Adobe,
        // changes the colors if missing) stay.
        return !(marker == 0xE1 || (marker >= 0xE3 && marker <= 0xED) || marker == 0xEF || marker == 0xFE);
    }

    // ---- WebP ----

    private static CoverCheck webp(byte[] b) {
        if (u32le(b, 4) + 8 != b.length) {
            throw invalid();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(b.length);
        out.write(b, 0, 12);
        int pos = 12;
        boolean first = true;
        boolean hasImage = false;
        boolean hasCanvas = false;
        long canvasWidth = 0;
        long canvasHeight = 0;
        int width = 0;
        int height = 0;
        while (pos < b.length) {
            need(b, pos, 8);
            String type = ascii(b, pos, 4);
            long size = u32le(b, pos + 4);
            long end = pos + 8L + size + (size & 1); // chunks are padded to an even size
            if (end > b.length) {
                throw invalid();
            }
            int data = pos + 8;
            switch (type) {
                case "VP8X" -> {
                    if (!first || size < 10) {
                        throw invalid();
                    }
                    if ((u8(b, data) & 0x02) != 0) {
                        throw new Rejection(ProductCoverRejection.COVER_ANIMATED);
                    }
                    canvasWidth = 1L + u24le(b, data + 4);
                    canvasHeight = 1L + u24le(b, data + 7);
                    checkSide(canvasWidth, canvasHeight);
                    hasCanvas = true;
                    byte[] chunk = Arrays.copyOfRange(b, pos, (int) end);
                    chunk[8] &= (byte) ~0x0C; // EXIF (0x08) and XMP (0x04) flags: those chunks are dropped
                    out.write(chunk, 0, chunk.length);
                }
                case "ANIM", "ANMF" -> throw new Rejection(ProductCoverRejection.COVER_ANIMATED);
                case "VP8 " -> {
                    if (hasImage
                            || size < 10
                            || u8(b, data + 3) != 0x9D
                            || u8(b, data + 4) != 0x01
                            || u8(b, data + 5) != 0x2A) {
                        throw invalid();
                    }
                    width = u16le(b, data + 6) & 0x3FFF;
                    height = u16le(b, data + 8) & 0x3FFF;
                    hasImage = true;
                    out.write(b, pos, (int) (end - pos));
                }
                case "VP8L" -> {
                    if (hasImage || size < 5 || u8(b, data) != 0x2F) {
                        throw invalid();
                    }
                    long bits = u32le(b, data + 1);
                    width = (int) (bits & 0x3FFF) + 1;
                    height = (int) ((bits >> 14) & 0x3FFF) + 1;
                    hasImage = true;
                    out.write(b, pos, (int) (end - pos));
                }
                default -> {
                    // EXIF, XMP and unknown chunks are dropped
                    if (WEBP_KEPT.contains(type)) {
                        out.write(b, pos, (int) (end - pos));
                    }
                }
            }
            first = false;
            pos = (int) end;
        }
        if (!hasImage) {
            throw invalid();
        }
        checkSide(width, height);
        if (hasCanvas && (canvasWidth != width || canvasHeight != height)) {
            throw invalid();
        }
        byte[] cleaned = out.toByteArray();
        long riffSize = cleaned.length - 8L;
        for (int i = 0; i < 4; i++) {
            cleaned[4 + i] = (byte) (riffSize >> (8 * i));
        }
        return new CoverCheck.Valid(CoverFormat.WEBP, width, height, cleaned);
    }

    // ---- helpers ----

    private static void checkSide(long width, long height) {
        if (width < 1 || height < 1) {
            throw invalid();
        }
        if (width > MAX_SIDE || height > MAX_SIDE) {
            throw new Rejection(ProductCoverRejection.COVER_TOO_MANY_PIXELS);
        }
    }

    private static Rejection invalid() {
        return new Rejection(ProductCoverRejection.COVER_INVALID);
    }

    private static CoverCheck reject(ProductCoverRejection reason) {
        return new CoverCheck.Rejected(reason);
    }

    /** Fails unless {@code count} bytes starting at {@code pos} exist. */
    private static void need(byte[] b, long pos, long count) {
        if (pos < 0 || count < 0 || pos + count > b.length) {
            throw invalid();
        }
    }

    private static int u8(byte[] b, int i) {
        need(b, i, 1);
        return b[i] & 0xFF;
    }

    private static int u16(byte[] b, int i) {
        need(b, i, 2);
        return (b[i] & 0xFF) << 8 | (b[i + 1] & 0xFF);
    }

    private static int u16le(byte[] b, int i) {
        need(b, i, 2);
        return (b[i + 1] & 0xFF) << 8 | (b[i] & 0xFF);
    }

    private static int u24le(byte[] b, int i) {
        need(b, i, 3);
        return (b[i + 2] & 0xFF) << 16 | (b[i + 1] & 0xFF) << 8 | (b[i] & 0xFF);
    }

    // The uint32 values are longs so a high bit never turns into a negative length.
    private static long u32be(byte[] b, int i) {
        need(b, i, 4);
        return (b[i] & 0xFFL) << 24 | (b[i + 1] & 0xFFL) << 16 | (b[i + 2] & 0xFFL) << 8 | (b[i + 3] & 0xFFL);
    }

    private static long u32le(byte[] b, int i) {
        need(b, i, 4);
        return (b[i + 3] & 0xFFL) << 24 | (b[i + 2] & 0xFFL) << 16 | (b[i + 1] & 0xFFL) << 8 | (b[i] & 0xFFL);
    }

    private static String ascii(byte[] b, int i, int count) {
        need(b, i, count);
        return new String(b, i, count, StandardCharsets.ISO_8859_1);
    }

    private static boolean startsWith(byte[] b, byte[] prefix) {
        return b.length >= prefix.length && regionMatches(b, 0, prefix);
    }

    private static boolean regionMatches(byte[] b, int at, byte[] expected) {
        for (int i = 0; i < expected.length; i++) {
            if (b[at + i] != expected[i]) {
                return false;
            }
        }
        return true;
    }
}
