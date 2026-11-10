package br.com.sergio.sapiensia.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfFileValidatorTest {

    private static final String PDF = "application/pdf";

    @TempDir
    Path dir;

    private final PdfFileValidator validator = new PdfFileValidator(1000, Duration.ofSeconds(20));

    private Path write(byte[] bytes) throws IOException {
        return Files.write(Files.createTempFile(dir, "upload", ".tmp"), bytes);
    }

    private static PdfCheck rejected(ProductFileRejection reason) {
        return new PdfCheck.Rejected(reason);
    }

    @Test
    @DisplayName("SEC-043: PDF válido de 1 página é aceito")
    void validPdf() throws Exception {
        assertThat(validator.check(write(TestPdfs.pages(1)), "a.pdf", PDF)).isEqualTo(new PdfCheck.Valid(1));
    }

    @Test
    @DisplayName("SEC-043: arquivo de texto com extensão .pdf é recusado (FILE_NOT_PDF)")
    void textFile() throws Exception {
        Path file = write("hello, not a pdf".getBytes(StandardCharsets.UTF_8));
        assertThat(validator.check(file, "a.pdf", PDF)).isEqualTo(rejected(ProductFileRejection.FILE_NOT_PDF));
    }

    @Test
    @DisplayName("SEC-043: PNG renomeado para .pdf é recusado")
    void renamedPng() throws Exception {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
        assertThat(validator.check(write(png), "a.pdf", PDF)).isEqualTo(rejected(ProductFileRejection.FILE_NOT_PDF));
    }

    @Test
    @DisplayName("SEC-043: PDF de verdade com extensão .txt é recusado")
    void wrongExtension() throws Exception {
        Path file = write(TestPdfs.pages(1));
        assertThat(validator.check(file, "a.txt", PDF)).isEqualTo(rejected(ProductFileRejection.FILE_NOT_PDF));
        assertThat(validator.check(file, "x.pdf.exe", PDF)).isEqualTo(rejected(ProductFileRejection.FILE_NOT_PDF));
        assertThat(validator.check(file, "a.pdf\u0000.exe", PDF))
                .isEqualTo(rejected(ProductFileRejection.FILE_NOT_PDF));
        assertThat(validator.check(file, "../../etc/passwd.PDF", PDF)).isEqualTo(new PdfCheck.Valid(1));
    }

    @Test
    @DisplayName("SEC-043: MIME declarado image/png é recusado")
    void declaredMime() throws Exception {
        Path file = write(TestPdfs.pages(1));
        assertThat(validator.check(file, "a.pdf", "image/png")).isEqualTo(rejected(ProductFileRejection.FILE_NOT_PDF));
        assertThat(validator.check(file, "a.pdf", "application/octet-stream")).isEqualTo(new PdfCheck.Valid(1));
        assertThat(validator.check(file, "a.pdf", "application/x-pdf")).isEqualTo(new PdfCheck.Valid(1));
    }

    @Test
    @DisplayName("SEC-043: PDF de 1.000 páginas é aceito e o de 1.001 é recusado (FILE_TOO_MANY_PAGES)")
    void pageLimit() throws Exception {
        assertThat(validator.check(write(TestPdfs.pages(1000)), "a.pdf", PDF)).isEqualTo(new PdfCheck.Valid(1000));
        assertThat(validator.check(write(TestPdfs.pages(1001)), "a.pdf", PDF))
                .isEqualTo(rejected(ProductFileRejection.FILE_TOO_MANY_PAGES));
    }

    @Test
    @DisplayName("SEC-043: árvore de páginas com /Count mentiroso é contada de verdade")
    void lyingCount() throws Exception {
        assertThat(validator.check(write(TestPdfs.lyingCount(1500, 10)), "a.pdf", PDF))
                .isEqualTo(rejected(ProductFileRejection.FILE_TOO_MANY_PAGES));
        assertThat(validator.check(write(TestPdfs.lyingCount(3, 5000)), "a.pdf", PDF))
                .isEqualTo(new PdfCheck.Valid(3));
    }

    @Test
    @DisplayName("SEC-043: árvore de páginas com ciclo termina rápido, sem contar páginas infinitas")
    void pageTreeCycle() throws Exception {
        PdfFileValidator shortTimeout = new PdfFileValidator(1000, Duration.ofSeconds(3));
        long start = System.nanoTime();
        PdfCheck result = shortTimeout.check(write(TestPdfs.pageTreeCycle()), "a.pdf", PDF);
        // PDFBox skips the loop on its own (the file is accepted with the pages it really has); a parser error or
        // the page limit would be fine too. What matters: it ends, and never reports an unbounded count.
        if (result instanceof PdfCheck.Valid valid) {
            assertThat(valid.pageCount()).isLessThanOrEqualTo(3);
        } else {
            assertThat(result)
                    .isIn(
                            rejected(ProductFileRejection.FILE_INVALID),
                            rejected(ProductFileRejection.FILE_TOO_MANY_PAGES));
        }
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("SEC-043: PDF sem nenhuma página é recusado (FILE_INVALID)")
    void zeroPages() throws Exception {
        assertThat(validator.check(write(TestPdfs.zeroPages()), "a.pdf", PDF))
                .isEqualTo(rejected(ProductFileRejection.FILE_INVALID));
    }

    @Test
    @DisplayName("SEC-043: PDF malformado (cabeçalho seguido de lixo) é recusado (FILE_INVALID)")
    void malformed() throws Exception {
        Path file = write("%PDF-1.7\nthis is garbage, not objects\n".getBytes(StandardCharsets.UTF_8));
        assertThat(validator.check(file, "a.pdf", PDF)).isEqualTo(rejected(ProductFileRejection.FILE_INVALID));
    }

    @Test
    @DisplayName("SEC-043: PDF truncado no meio é recusado ou aceito só se o parser tolerante o abrir")
    void truncated() throws Exception {
        byte[] full = TestPdfs.pages(5);
        byte[] half = Arrays.copyOf(full, full.length / 2);
        PdfCheck result = validator.check(write(half), "a.pdf", PDF);
        // Never an exception and never more pages than the file really had.
        assertThat(result).isInstanceOfAny(PdfCheck.Valid.class, PdfCheck.Rejected.class);
        if (result instanceof PdfCheck.Valid valid) {
            assertThat(valid.pageCount()).isBetween(1, 5);
        } else {
            assertThat(result).isEqualTo(rejected(ProductFileRejection.FILE_INVALID));
        }
    }

    @Test
    @DisplayName("SEC-043: PDF com senha de abertura e PDF só com senha de dono são recusados (FILE_ENCRYPTED)")
    void encrypted() throws Exception {
        assertThat(validator.check(write(TestPdfs.encryptedWithUserPassword()), "a.pdf", PDF))
                .isEqualTo(rejected(ProductFileRejection.FILE_ENCRYPTED));
        assertThat(validator.check(write(TestPdfs.encryptedWithOwnerPasswordOnly()), "a.pdf", PDF))
                .isEqualTo(rejected(ProductFileRejection.FILE_ENCRYPTED));
    }

    @Test
    @DisplayName("SEC-047: validação que passa do tempo limite é interrompida e recusada")
    void timeout() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        PdfFileValidator slow = new PdfFileValidator(1000, Duration.ofMillis(200), file -> {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                interrupted.countDown();
                throw new IOException(e);
            }
            throw new IOException("stub opener never returns a document");
        });
        long start = System.nanoTime();
        assertThat(slow.check(write(TestPdfs.pages(1)), "a.pdf", PDF))
                .isEqualTo(rejected(ProductFileRejection.FILE_INVALID));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("SEC-047: validações em sequência nunca recebem ocupado")
    void sequentialCallsAreNeverBusy() throws Exception {
        Path file = write(TestPdfs.pages(1));
        for (int i = 0; i < 200; i++) {
            assertThat(validator.check(file, "a.pdf", PDF)).isEqualTo(new PdfCheck.Valid(1));
        }
    }

    @Test
    @DisplayName("SEC-047: segunda validação simultânea recebe ocupado")
    void busy() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PdfFileValidator blocking = new PdfFileValidator(1000, Duration.ofSeconds(30), file -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
            throw new IOException("stub opener never returns a document");
        });
        Path file = write(TestPdfs.pages(1));
        CompletableFuture<PdfCheck> first = CompletableFuture.supplyAsync(() -> blocking.check(file, "a.pdf", PDF));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            assertThatThrownBy(() -> blocking.check(file, "b.pdf", PDF)).isInstanceOf(ValidatorBusyException.class);
        } finally {
            release.countDown();
        }
        assertThat(first.get(5, TimeUnit.SECONDS)).isInstanceOf(PdfCheck.Rejected.class);
        // Once the first one is done the validator is free again.
        assertThat(validator.check(file, "a.pdf", PDF)).isEqualTo(new PdfCheck.Valid(1));
    }

    @Test
    @DisplayName("SEC-046 SEC-047: PDF com /URI, /GoToR, /Launch e JavaScript não abre conexão na validação")
    void activeContentOpensNoConnection() throws Exception {
        AtomicInteger accepted = new AtomicInteger();
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread acceptor = new Thread(() -> {
                try {
                    while (true) {
                        try (Socket ignored = server.accept()) {
                            accepted.incrementAndGet();
                        }
                    }
                } catch (IOException closed) {
                    // the server socket was closed: stop
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();

            byte[] bytes = TestPdfs.withActiveContent(server.getLocalPort());
            Path file = write(bytes);
            assertThat(validator.check(file, "a.pdf", PDF)).isEqualTo(new PdfCheck.Valid(1));
            Thread.sleep(500);

            assertThat(accepted.get()).isZero();
            assertThat(Files.readAllBytes(file)).isEqualTo(bytes);
            // The fixture really carries the active content, so the test cannot pass vacuously.
            String text = new String(bytes, StandardCharsets.ISO_8859_1);
            assertThat(text).contains("/URI", "/GoToR", "/Launch", "/JavaScript", "/OpenAction");
            assertThat(text).contains("127.0.0.1:" + server.getLocalPort());
        }
    }
}
