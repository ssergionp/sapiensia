package br.com.sergio.sapiensia.product;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdfparser.PDFParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Checks an uploaded PDF that already sits in a temporary file (SEC-043, SEC-046, SEC-047).
 *
 * <p>The only PDFBox calls allowed here: {@code new PDFParser(RandomAccessReadBufferedFile, "")} with
 * {@code parse(true)}, {@code PDDocument.isEncrypted()}, iterating {@code getPages()} and {@code close()}.
 * Never load from a byte array or stream, and never touch actions, XFA, metadata, embedded files, fonts, text
 * or rendering: the file is only parsed, never interpreted.
 */
@Component
public class PdfFileValidator {

    /** Opens the PDF; replaceable in tests. */
    interface PdfOpener {
        PDDocument open(RandomAccessReadBufferedFile file) throws IOException;
    }

    static final long MAX_BYTES = 52_428_800L;
    private static final Set<String> ACCEPTED_TYPES =
            Set.of("application/pdf", "application/x-pdf", "application/octet-stream");

    private final int maxPages;
    private final Duration timeout;
    private final PdfOpener opener;
    // One validation at a time and no queue: a second caller is told to retry (SEC-047). The permit is released
    // by the worker itself, so it stays taken after a timeout until the worker has really stopped.
    private final Semaphore permit = new Semaphore(1);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "pdf-validation");
        thread.setDaemon(true);
        return thread;
    });

    @Autowired
    public PdfFileValidator(
            @Value("${app.product-file.max-pages}") int maxPages,
            @Value("${app.product-file.validation-timeout}") Duration timeout) {
        this(maxPages, timeout, file -> new PDFParser(file, "").parse(true));
    }

    PdfFileValidator(int maxPages, Duration timeout, PdfOpener opener) {
        this.maxPages = maxPages;
        this.timeout = timeout;
        this.opener = opener;
    }

    public PdfCheck check(Path file, String submittedName, String declaredContentType) {
        if (!hasPdfExtension(submittedName) || !isAcceptedType(declaredContentType)) {
            return reject(ProductFileRejection.FILE_NOT_PDF);
        }
        try {
            if (Files.size(file) > MAX_BYTES) {
                return reject(ProductFileRejection.FILE_TOO_LARGE);
            }
            if (!hasPdfSignature(file)) {
                return reject(ProductFileRejection.FILE_NOT_PDF);
            }
        } catch (IOException e) {
            return reject(ProductFileRejection.FILE_INVALID);
        }
        return parse(file);
    }

    private PdfCheck parse(Path file) {
        if (!permit.tryAcquire()) {
            throw new ValidatorBusyException();
        }
        RandomAccessReadBufferedFile source;
        Future<PdfCheck> future;
        try {
            source = new RandomAccessReadBufferedFile(file);
            RandomAccessReadBufferedFile opened = source;
            future = executor.submit(() -> {
                try {
                    return inspect(opened);
                } finally {
                    permit.release();
                }
            });
        } catch (IOException | RuntimeException e) {
            permit.release();
            return reject(ProductFileRejection.FILE_INVALID);
        }
        try {
            return future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            // Closing the file makes the parser fail on its next read.
            closeQuietly(source);
            return reject(ProductFileRejection.FILE_INVALID);
        } catch (InterruptedException e) {
            future.cancel(true);
            closeQuietly(source);
            Thread.currentThread().interrupt();
            return reject(ProductFileRejection.FILE_INVALID);
        } catch (ExecutionException e) {
            return reject(ProductFileRejection.FILE_INVALID);
        }
    }

    private PdfCheck inspect(RandomAccessReadBufferedFile source) {
        PDDocument document = null;
        try {
            document = opener.open(source);
            if (document.isEncrypted()) {
                return reject(ProductFileRejection.FILE_ENCRYPTED);
            }
            // Count the pages one by one, never trusting /Count; stop right after the limit.
            int count = 0;
            for (PDPage ignored : document.getPages()) {
                count++;
                if (count > maxPages) {
                    return reject(ProductFileRejection.FILE_TOO_MANY_PAGES);
                }
            }
            return count == 0 ? reject(ProductFileRejection.FILE_INVALID) : new PdfCheck.Valid(count);
        } catch (InvalidPasswordException e) {
            return reject(ProductFileRejection.FILE_ENCRYPTED);
        } catch (IOException | RuntimeException e) {
            return reject(ProductFileRejection.FILE_INVALID);
        } finally {
            if (document != null) {
                closeQuietly(document);
            }
            closeQuietly(source);
        }
    }

    private static boolean hasPdfExtension(String name) {
        if (name == null) {
            return false;
        }
        String last = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        if (last.chars().anyMatch(Character::isISOControl)) {
            return false;
        }
        return last.toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    private static boolean isAcceptedType(String contentType) {
        if (contentType == null) {
            return false;
        }
        String base = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return ACCEPTED_TYPES.contains(base);
    }

    private static boolean hasPdfSignature(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(7);
            if (head.length < 7) {
                return false;
            }
            String text = new String(head, StandardCharsets.ISO_8859_1);
            return text.startsWith("%PDF-1.") || text.startsWith("%PDF-2.");
        }
    }

    private static PdfCheck reject(ProductFileRejection reason) {
        return new PdfCheck.Rejected(reason);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // nothing useful to do; the temporary file is deleted by the caller
        }
    }
}
