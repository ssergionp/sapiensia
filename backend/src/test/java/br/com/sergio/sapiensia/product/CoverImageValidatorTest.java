package br.com.sergio.sapiensia.product;

import static br.com.sergio.sapiensia.product.TestImages.concat;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoverImageValidatorTest {

    @TempDir
    Path dir;

    private final CoverImageValidator validator = new CoverImageValidator();

    private CoverCheck check(byte[] bytes) throws IOException {
        return validator.check(Files.write(Files.createTempFile(dir, "upload", ".tmp"), bytes));
    }

    private static CoverCheck rejected(ProductCoverRejection reason) {
        return new CoverCheck.Rejected(reason);
    }

    private CoverCheck.Valid valid(byte[] bytes) throws IOException {
        CoverCheck result = check(bytes);
        assertThat(result).isInstanceOf(CoverCheck.Valid.class);
        return (CoverCheck.Valid) result;
    }

    // ---- accepted formats ----

    @Test
    @DisplayName("SEC-043: accepts a PNG and reports its format and size")
    void shouldAcceptPng() throws IOException {
        CoverCheck.Valid result = valid(TestImages.png(10, 20));
        assertThat(result.format()).isEqualTo(CoverFormat.PNG);
        assertThat(result.width()).isEqualTo(10);
        assertThat(result.height()).isEqualTo(20);
    }

    @Test
    @DisplayName("SEC-043: accepts a JPEG and reports its format and size")
    void shouldAcceptJpeg() throws IOException {
        CoverCheck.Valid result = valid(TestImages.jpeg(10, 20));
        assertThat(result.format()).isEqualTo(CoverFormat.JPEG);
        assertThat(result.width()).isEqualTo(10);
        assertThat(result.height()).isEqualTo(20);
    }

    @Test
    @DisplayName("SEC-043: accepts a WebP and reports its format and size")
    void shouldAcceptWebp() throws IOException {
        CoverCheck.Valid result = valid(TestImages.webpLossless1x1());
        assertThat(result.format()).isEqualTo(CoverFormat.WEBP);
        assertThat(result.width()).isEqualTo(1);
        assertThat(result.height()).isEqualTo(1);
    }

    @Test
    @DisplayName("SEC-043: the format comes from the bytes, never from the file name")
    void shouldIdentifyFormatByBytesNotByName() throws IOException {
        Path file = Files.write(dir.resolve("capa.jpg"), TestImages.png(10, 20));
        CoverCheck result = validator.check(file);
        assertThat(result).isInstanceOf(CoverCheck.Valid.class);
        assertThat(((CoverCheck.Valid) result).format()).isEqualTo(CoverFormat.PNG);
    }

    @Test
    void shouldExposeMediaTypeAndExtensionOfEachFormat() {
        assertThat(CoverFormat.PNG.mediaType()).isEqualTo("image/png");
        assertThat(CoverFormat.PNG.extension()).isEqualTo("png");
        assertThat(CoverFormat.JPEG.mediaType()).isEqualTo("image/jpeg");
        assertThat(CoverFormat.JPEG.extension()).isEqualTo("jpg");
        assertThat(CoverFormat.WEBP.mediaType()).isEqualTo("image/webp");
        assertThat(CoverFormat.WEBP.extension()).isEqualTo("webp");
    }

    // ---- not an image ----

    @Test
    @DisplayName("SEC-043: SVG, GIF, PDF and BMP are not accepted")
    void shouldRejectOtherFormats() throws IOException {
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
                .getBytes(StandardCharsets.UTF_8);
        byte[] gif = "GIF89a\u0001\u0000\u0001\u0000".getBytes(StandardCharsets.ISO_8859_1);
        byte[] pdf = "%PDF-1.7\n%%EOF".getBytes(StandardCharsets.ISO_8859_1);
        byte[] bmp = concat("BM".getBytes(StandardCharsets.US_ASCII), new byte[60]);
        for (byte[] other : List.of(svg, gif, pdf, bmp)) {
            assertThat(check(other)).isEqualTo(rejected(ProductCoverRejection.COVER_NOT_IMAGE));
        }
    }

    @Test
    void shouldRejectEmptyFile() throws IOException {
        assertThat(check(new byte[0])).isEqualTo(rejected(ProductCoverRejection.COVER_EMPTY));
    }

    // ---- size ----

    @Test
    @DisplayName("SEC-047: exactly 2 MiB passes the size check, one byte more is too large")
    void shouldApplySizeLimit() throws IOException {
        byte[] atLimit = new byte[(int) CoverImageValidator.MAX_BYTES];
        byte[] signature = TestImages.png(1, 1);
        System.arraycopy(signature, 0, atLimit, 0, 8);
        assertThat(check(atLimit)).isNotEqualTo(rejected(ProductCoverRejection.COVER_TOO_LARGE));
        assertThat(check(new byte[(int) CoverImageValidator.MAX_BYTES + 1]))
                .isEqualTo(rejected(ProductCoverRejection.COVER_TOO_LARGE));
    }

    // ---- dimensions ----

    @Test
    @DisplayName("SEC-047: sides above 4000 px are refused from the header, without decoding")
    void shouldRejectTooManyPixels() throws IOException {
        for (int[] size : new int[][] {{4001, 10}, {10, 4001}, {30000, 30000}}) {
            assertThat(check(TestImages.pngHeaderOnly(size[0], size[1])))
                    .as(size[0] + "x" + size[1])
                    .isEqualTo(rejected(ProductCoverRejection.COVER_TOO_MANY_PIXELS));
        }
        CoverCheck.Valid max = valid(TestImages.pngHeaderOnly(4000, 4000));
        assertThat(max.width()).isEqualTo(4000);
        assertThat(max.height()).isEqualTo(4000);
    }

    @Test
    void shouldRejectJpegWithZeroWidth() throws IOException {
        byte[] jpeg = TestImages.jpeg(10, 20);
        int sof = indexOf(jpeg, new byte[] {(byte) 0xFF, (byte) 0xC0});
        // marker(2) length(2) precision(1) height(2) width(2)
        jpeg[sof + 7] = 0;
        jpeg[sof + 8] = 0;
        assertThat(check(jpeg)).isEqualTo(rejected(ProductCoverRejection.COVER_INVALID));
    }

    @Test
    void shouldRejectJpegWithOversizedSide() throws IOException {
        byte[] jpeg = TestImages.jpeg(10, 20);
        int sof = indexOf(jpeg, new byte[] {(byte) 0xFF, (byte) 0xC0});
        jpeg[sof + 5] = (byte) (4001 >> 8);
        jpeg[sof + 6] = (byte) 4001;
        assertThat(check(jpeg)).isEqualTo(rejected(ProductCoverRejection.COVER_TOO_MANY_PIXELS));
    }

    // ---- animation ----

    @Test
    @DisplayName("SEC-043: animated PNG and WebP are refused")
    void shouldRejectAnimation() throws IOException {
        byte[] apng = TestImages.withPngChunk(TestImages.png(4, 4), "acTL", new byte[8]);
        assertThat(check(apng)).isEqualTo(rejected(ProductCoverRejection.COVER_ANIMATED));

        byte[] animatedFlag = TestImages.webpVp8xWith(TestImages.webpLossless1x1(), false, true);
        assertThat(check(animatedFlag)).isEqualTo(rejected(ProductCoverRejection.COVER_ANIMATED));

        byte[] animChunk = TestImages.webpRiff(
                TestImages.vp8xChunk(0),
                TestImages.webpChunk("ANIM", new byte[6]),
                TestImages.vp8lChunk(TestImages.webpLossless1x1()));
        assertThat(check(animChunk)).isEqualTo(rejected(ProductCoverRejection.COVER_ANIMATED));
    }

    // ---- G2: fail closed ----

    @Test
    @DisplayName("SEC-043: a PNG chunk longer than the file, or with the uint32 high bit set, is invalid")
    void shouldRejectPngWithLyingChunkLength() throws IOException {
        for (long lie : new long[] {0x7FFFFFFFL, 0x80000000L, 0xFFFFFFFFL, 100_000L}) {
            byte[] png = TestImages.png(10, 20);
            // the chunk after IHDR: 8 signature + 25 IHDR
            System.arraycopy(TestImages.be32(lie), 0, png, 33, 4);
            assertThat(check(png)).as("length " + lie).isEqualTo(rejected(ProductCoverRejection.COVER_INVALID));
        }
    }

    @Test
    void shouldRejectTruncatedPng() throws IOException {
        byte[] png = TestImages.png(10, 20);
        assertThat(check(Arrays.copyOf(png, png.length - 12))).isEqualTo(rejected(ProductCoverRejection.COVER_INVALID));
        assertThat(check(Arrays.copyOf(png, 20))).isEqualTo(rejected(ProductCoverRejection.COVER_INVALID));
    }

    @Test
    void shouldRejectJpegWithLyingSegmentLength() throws IOException {
        byte[] jpeg = TestImages.jpeg(10, 20);
        // first segment (APP0 or DQT) length
        jpeg[4] = (byte) 0xFF;
        jpeg[5] = (byte) 0xFF;
        assertThat(check(jpeg)).isEqualTo(rejected(ProductCoverRejection.COVER_INVALID));
    }

    @Test
    void shouldRejectJpegSegmentShorterThanItsOwnLength() throws IOException {
        byte[] jpeg = concat(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 1}, new byte[20]);
        assertThat(check(jpeg)).isEqualTo(rejected(ProductCoverRejection.COVER_INVALID));
    }

    @Test
    void shouldRejectJpegWithoutFrameHeader() throws IOException {
        byte[] jpeg = concat(
                new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xFE, 0, 4, 'a', 'b'},
                new byte[] {(byte) 0xFF, (byte) 0xDA, 0, 8, 1, 1, 0, 0, 63, 0},
                new byte[] {1, 2, 3, (byte) 0xFF, (byte) 0xD9});
        assertThat(check(jpeg)).isEqualTo(rejected(ProductCoverRejection.COVER_INVALID));
    }

    @Test
    void shouldRejectJpegTruncatedBeforeEoi() throws IOException {
        byte[] jpeg = TestImages.jpeg(10, 20);
        assertThat(check(Arrays.copyOf(jpeg, jpeg.length - 2)))
                .isEqualTo(rejected(ProductCoverRejection.COVER_INVALID));
    }

    @Test
    void shouldRejectWebpWithWrongRiffSize() throws IOException {
        byte[] larger = TestImages.webpLossless1x1();
        System.arraycopy(TestImages.le32(larger.length + 100L), 0, larger, 4, 4);
        assertThat(check(larger)).isEqualTo(rejected(ProductCoverRejection.COVER_INVALID));

        byte[] smaller = TestImages.webpLossless1x1();
        System.arraycopy(TestImages.le32(smaller.length - 20L), 0, smaller, 4, 4);
        assertThat(check(smaller)).isEqualTo(rejected(ProductCoverRejection.COVER_INVALID));
    }

    @Test
    void shouldRejectWebpChunkWithLyingSize() throws IOException {
        for (long lie : new long[] {1000L, 0x80000000L, 0xFFFFFFFFL}) {
            byte[] webp = TestImages.webpLossless1x1();
            // VP8L chunk size sits right after the 12-byte RIFF header and the 4-byte type
            System.arraycopy(TestImages.le32(lie), 0, webp, 16, 4);
            assertThat(check(webp)).as("size " + lie).isEqualTo(rejected(ProductCoverRejection.COVER_INVALID));
        }
    }

    @Test
    void shouldRejectWebpWithTooShortVp8x() throws IOException {
        byte[] webp = TestImages.webpRiff(
                TestImages.webpChunk("VP8X", new byte[4]), TestImages.vp8lChunk(TestImages.webpLossless1x1()));
        assertThat(check(webp)).isEqualTo(rejected(ProductCoverRejection.COVER_INVALID));
    }

    @Test
    @DisplayName(
            "SEC-043: prefixes and mutations only ever give a designed result, and a Valid output is accepted again")
    void shouldNeverThrowOnPrefixesOrMutations() throws IOException {
        List<byte[]> samples = List.of(
                TestImages.png(10, 20),
                TestImages.jpeg(10, 20),
                TestImages.progressiveJpeg(40, 40),
                TestImages.webpLossless1x1(),
                TestImages.webpVp8xWith(TestImages.webpLossless1x1(), true, false));
        Random random = new Random(42);
        for (byte[] sample : samples) {
            for (int length = 0; length <= sample.length; length++) {
                byte[] prefix = Arrays.copyOf(sample, length);
                CoverCheck result = parseWithoutSafetyNet(prefix);
                if (length < sample.length) {
                    assertThat(result).as("prefix of %d bytes", length).isNotInstanceOf(CoverCheck.Valid.class);
                }
            }
        }
        for (int i = 0; i < 1000; i++) {
            byte[] mutated = samples.get(i % samples.size()).clone();
            int changes = 1 + random.nextInt(4);
            for (int c = 0; c < changes; c++) {
                mutated[random.nextInt(mutated.length)] = (byte) random.nextInt(256);
            }
            if (parseWithoutSafetyNet(mutated) instanceof CoverCheck.Valid first) {
                CoverCheck.Valid again = valid(first.cleaned());
                assertThat(again.format()).isEqualTo(first.format());
                assertThat(again.width()).isEqualTo(first.width());
                assertThat(again.height()).isEqualTo(first.height());
            }
        }
    }

    /** Runs the walking code without the catch-all, so a swallowed bug (say, an out-of-bounds index) fails the test. */
    private static CoverCheck parseWithoutSafetyNet(byte[] bytes) {
        return CoverImageValidator.parse(bytes);
    }

    @Test
    @DisplayName("SEC-043: EXIF and a comment hidden between progressive scans are removed, the image still decodes")
    void shouldCleanMetadataBetweenProgressiveScans() throws IOException {
        byte[] progressive = TestImages.progressiveJpeg(40, 30);
        byte[] sos = {(byte) 0xFF, (byte) 0xDA};
        int firstScan = indexOf(progressive, sos);
        int secondScan =
                firstScan + 2 + indexOf(Arrays.copyOfRange(progressive, firstScan + 2, progressive.length), sos);
        assertThat(secondScan).isGreaterThan(firstScan + 2);
        byte[] exif = concat("Exif\0\0".getBytes(StandardCharsets.ISO_8859_1), new byte[40]);
        byte[] dirty = concat(
                Arrays.copyOfRange(progressive, 0, secondScan),
                new byte[] {(byte) 0xFF, (byte) 0xE1},
                TestImages.be16(exif.length + 2),
                exif,
                new byte[] {(byte) 0xFF, (byte) 0xFE},
                TestImages.be16(9),
                "secret!".getBytes(StandardCharsets.US_ASCII),
                Arrays.copyOfRange(progressive, secondScan, progressive.length),
                "PK tail".getBytes(StandardCharsets.US_ASCII));

        CoverCheck.Valid result = valid(dirty);

        assertThat(indexOf(result.cleaned(), "Exif".getBytes(StandardCharsets.ISO_8859_1)))
                .isEqualTo(-1);
        assertThat(indexOf(result.cleaned(), "secret!".getBytes(StandardCharsets.US_ASCII)))
                .isEqualTo(-1);
        assertThat(indexOf(result.cleaned(), "PK tail".getBytes(StandardCharsets.US_ASCII)))
                .isEqualTo(-1);
        assertThat(result.cleaned()).isEqualTo(progressive);
        var decoded = ImageIO.read(new ByteArrayInputStream(result.cleaned()));
        assertThat(decoded).isNotNull();
        assertThat(decoded.getWidth()).isEqualTo(40);
        assertThat(decoded.getHeight()).isEqualTo(30);
    }

    // ---- cleaning ----

    @Test
    @DisplayName("SEC-043: PNG text and EXIF chunks are removed, the image data is kept, the tail after IEND is cut")
    void shouldCleanPng() throws IOException {
        byte[] original = TestImages.png(10, 20);
        byte[] dirty =
                TestImages.withPngChunk(original, "tEXt", "Author\0someone".getBytes(StandardCharsets.ISO_8859_1));
        dirty = TestImages.withPngChunk(dirty, "eXIf", "GPS-looking-data".getBytes(StandardCharsets.ISO_8859_1));
        dirty = concat(dirty, "<html><script>alert(1)</script>".getBytes(StandardCharsets.UTF_8));

        byte[] cleaned = valid(dirty).cleaned();

        assertThat(pngChunkTypes(cleaned)).doesNotContain("tEXt", "eXIf");
        assertThat(pngChunkTypes(cleaned)).containsExactlyElementsOf(pngChunkTypes(original));
        assertThat(cleaned).isEqualTo(original);
    }

    @Test
    @DisplayName("SEC-043: JPEG EXIF (with an embedded thumbnail) and comments are removed, JFIF and ICC are kept")
    void shouldCleanJpeg() throws IOException {
        byte[] original = TestImages.jpeg(10, 20);
        byte[] thumbnail = TestImages.jpeg(2, 2);
        byte[] exif = concat("Exif\0\0".getBytes(StandardCharsets.ISO_8859_1), thumbnail);
        byte[] icc = concat("ICC_PROFILE\0\1\1".getBytes(StandardCharsets.ISO_8859_1), new byte[16]);
        byte[] dirty = TestImages.withJpegSegment(original, 0xE1, exif);
        dirty = TestImages.withJpegSegment(dirty, 0xFE, "made with a phone".getBytes(StandardCharsets.US_ASCII));
        dirty = TestImages.withJpegSegment(dirty, 0xE2, icc);
        dirty = TestImages.withJpegSegment(
                dirty, 0xE2, "MPF\0 not a color profile".getBytes(StandardCharsets.ISO_8859_1));
        dirty = concat(dirty, "PK\3\4 zip tail".getBytes(StandardCharsets.ISO_8859_1));

        CoverCheck.Valid result = valid(dirty);

        List<Integer> markers = jpegHeaderMarkers(result.cleaned());
        assertThat(markers).doesNotContain(0xE1, 0xFE);
        assertThat(markers).contains(0xE0, 0xE2);
        assertThat(markers.stream().filter(m -> m == 0xE2)).hasSize(1);
        assertThat(indexOf(result.cleaned(), "Exif".getBytes(StandardCharsets.ISO_8859_1)))
                .isEqualTo(-1);
        assertThat(indexOf(result.cleaned(), "PK".getBytes(StandardCharsets.ISO_8859_1)))
                .isEqualTo(-1);
        assertThat(result.cleaned()[result.cleaned().length - 2]).isEqualTo((byte) 0xFF);
        assertThat(result.cleaned()[result.cleaned().length - 1]).isEqualTo((byte) 0xD9);
    }

    @Test
    void shouldKeepJpegDecodableAfterCleaning() throws IOException {
        byte[] dirty = TestImages.withJpegSegment(
                TestImages.jpeg(10, 20),
                0xE1,
                concat("Exif\0\0".getBytes(StandardCharsets.ISO_8859_1), TestImages.jpeg(2, 2)));
        dirty = TestImages.withJpegSegment(dirty, 0xFE, "comment".getBytes(StandardCharsets.US_ASCII));

        CoverCheck.Valid result = valid(dirty);
        var decoded = ImageIO.read(new ByteArrayInputStream(result.cleaned()));

        assertThat(decoded).isNotNull();
        assertThat(decoded.getWidth()).isEqualTo(10);
        assertThat(decoded.getHeight()).isEqualTo(20);
    }

    @Test
    @DisplayName("SEC-043: WebP EXIF chunk is removed, its flag cleared and the RIFF size recomputed")
    void shouldCleanWebp() throws IOException {
        byte[] dirty = TestImages.webpVp8xWith(TestImages.webpLossless1x1(), true, false);

        byte[] cleaned = valid(dirty).cleaned();

        assertThat(indexOf(cleaned, "EXIF".getBytes(StandardCharsets.US_ASCII))).isEqualTo(-1);
        // flags byte of VP8X: 12 (RIFF header) + 8 (chunk header)
        assertThat(cleaned[20] & 0x08).isZero();
        long riffSize = (cleaned[4] & 0xFFL)
                | (cleaned[5] & 0xFFL) << 8
                | (cleaned[6] & 0xFFL) << 16
                | (cleaned[7] & 0xFFL) << 24;
        assertThat(riffSize).isEqualTo(cleaned.length - 8L);
        assertThat(cleaned.length).isLessThan(dirty.length);
        // the cleaned file is itself accepted
        assertThat(check(cleaned)).isInstanceOf(CoverCheck.Valid.class);
    }

    // ---- helpers ----

    private static List<String> pngChunkTypes(byte[] png) {
        List<String> types = new ArrayList<>();
        int pos = 8;
        while (pos + 8 <= png.length) {
            long length = ((png[pos] & 0xFFL) << 24)
                    | ((png[pos + 1] & 0xFFL) << 16)
                    | ((png[pos + 2] & 0xFFL) << 8)
                    | (png[pos + 3] & 0xFFL);
            types.add(new String(png, pos + 4, 4, StandardCharsets.ISO_8859_1));
            pos += 12 + (int) length;
        }
        return types;
    }

    /** Markers of the segments before the first SOS. */
    private static List<Integer> jpegHeaderMarkers(byte[] jpeg) {
        List<Integer> markers = new ArrayList<>();
        int pos = 2;
        while (pos + 4 <= jpeg.length) {
            int marker = jpeg[pos + 1] & 0xFF;
            if (marker == 0xDA) {
                break;
            }
            markers.add(marker);
            pos += 2 + (((jpeg[pos + 2] & 0xFF) << 8) | (jpeg[pos + 3] & 0xFF));
        }
        return markers;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
