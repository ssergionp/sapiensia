package br.com.sergio.sapiensia.admin;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/** Multipart bodies for the upload tests; big files are streamed, never held in one array. */
final class UploadBodies {

    private static final String BOUNDARY = "----sapiensia-test-boundary";
    private static final String HEADER = "%PDF-1.4\n";
    private static final String OBJECT_1 = "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n";
    private static final String OBJECT_2 = "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n";
    private static final String OBJECT_3 = "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] >>\nendobj\n";
    private static final String END_OF_STREAM = "\nendstream\nendobj\n";

    private UploadBodies() {}

    /** A ready multipart request body and its Content-Type. */
    record Upload(String contentType, HttpRequest.BodyPublisher publisher) {}

    @FunctionalInterface
    interface StreamSupplier {
        InputStream open();
    }

    static Upload of(String filename, String contentType, byte[] content) {
        return ofPart("file", filename, contentType, content);
    }

    /** A part with another name than {@code file} makes a request with no file at all. */
    static Upload ofPart(String partName, String filename, String contentType, byte[] content) {
        return stream(partName, filename, contentType, () -> new ByteArrayInputStream(content), content.length);
    }

    /**
     * A part whose content is produced by a new stream on every call. A negative {@code contentLength} sends the body
     * chunked, with no Content-Length (what a client that does not know the size would do).
     */
    static Upload stream(
            String partName, String filename, String contentType, StreamSupplier content, long contentLength) {
        byte[] head = ("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"" + partName + "\"; filename=\""
                        + filename + "\"\r\nContent-Type: " + contentType + "\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1);
        byte[] tail = ("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.ISO_8859_1);
        HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.ofInputStream(() -> new SequenceInputStream(
                new SequenceInputStream(new ByteArrayInputStream(head), content.open()),
                new ByteArrayInputStream(tail)));
        HttpRequest.BodyPublisher publisher = contentLength < 0
                ? body
                : HttpRequest.BodyPublishers.fromPublisher(body, head.length + contentLength + tail.length);
        return new Upload("multipart/form-data; boundary=" + BOUNDARY, publisher);
    }

    /** A stream of {@code length} filler bytes, produced on demand; {@code delivered} counts what was read. */
    static StreamSupplier filler(long length, AtomicLong delivered) {
        return () -> new Filler(length, delivered);
    }

    private static final class Filler extends InputStream {
        private long left;
        private final AtomicLong delivered;

        Filler(long length, AtomicLong delivered) {
            this.left = length;
            this.delivered = delivered;
        }

        @Override
        public int read() {
            if (left <= 0) {
                return -1;
            }
            left--;
            delivered.incrementAndGet();
            return 'x';
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (left <= 0) {
                return -1;
            }
            int count = (int) Math.min(length, left);
            Arrays.fill(buffer, offset, offset + count, (byte) 'x');
            left -= count;
            delivered.addAndGet(count);
            return count;
        }
    }

    /**
     * A valid one-page PDF of exactly {@code totalSize} bytes: the real objects, then one uncompressed stream object
     * holding filler bytes, then the xref. Generated on the fly so the test never holds the file in memory.
     */
    static StreamSupplier paddedPdf(long totalSize) {
        long fillerLength = totalSize;
        byte[] head = null;
        byte[] tail = null;
        // the lengths of head and tail depend on the digits of the numbers inside them, so settle by iterating
        for (int i = 0; i < 6; i++) {
            head = pdfHead(fillerLength);
            long xrefOffset = head.length + fillerLength + END_OF_STREAM.length();
            tail = pdfTail(xrefOffset);
            fillerLength = totalSize - head.length - tail.length;
        }
        byte[] finalHead = head;
        byte[] finalTail = tail;
        long finalFiller = fillerLength;
        if (finalHead.length + finalFiller + finalTail.length != totalSize) {
            throw new IllegalStateException("cannot reach the exact size");
        }
        return () -> new SequenceInputStream(
                new SequenceInputStream(new ByteArrayInputStream(finalHead), new Filler(finalFiller, new AtomicLong())),
                new ByteArrayInputStream(finalTail));
    }

    private static byte[] pdfHead(long fillerLength) {
        return (HEADER + OBJECT_1 + OBJECT_2 + OBJECT_3 + "4 0 obj\n<< /Length " + fillerLength + " >>\nstream\n")
                .getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] pdfTail(long xrefOffset) {
        long object1 = HEADER.length();
        long object2 = object1 + OBJECT_1.length();
        long object3 = object2 + OBJECT_2.length();
        long object4 = object3 + OBJECT_3.length();
        String xref =
                "xref\n0 5\n0000000000 65535 f \n" + entry(object1) + entry(object2) + entry(object3) + entry(object4);
        return (END_OF_STREAM + xref + "trailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n" + xrefOffset + "\n%%EOF\n")
                .getBytes(StandardCharsets.ISO_8859_1);
    }

    private static String entry(long offset) {
        return String.format(Locale.ROOT, "%010d 00000 n \n", offset);
    }
}
