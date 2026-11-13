package br.com.sergio.sapiensia.product;

import br.com.sergio.sapiensia.demo.DemoAccounts;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Local demo only: gives three of the seed Products a generated cover and PDF and publishes them, through the real
 * services (so the PDF, cover and metadata checks apply). The other seed Products stay DRAFT. Never logs file names.
 */
@Component
@Profile("local")
@ConditionalOnProperty(name = "app.demo.enabled", havingValue = "true")
@Order(3)
class DemoProductSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoProductSeeder.class);
    // the audit origin column is an inet: a literal "demo" is rejected by the database
    private static final String ORIGIN = "127.0.0.1";
    private static final List<UUID> DEMO_IDS = List.of(
            UUID.fromString("00000000-0000-4000-8000-000000000101"),
            UUID.fromString("00000000-0000-4000-8000-000000000103"),
            UUID.fromString("00000000-0000-4000-8000-000000000104"));
    private static final int COVER_WIDTH = 1200;
    private static final int COVER_HEIGHT = 1800;

    private final Products products;
    private final ProductCoverService covers;
    private final ProductFileService files;
    private final ProductPublicationService publication;
    private final JdbcClient jdbc;

    DemoProductSeeder(
            Products products,
            ProductCoverService covers,
            ProductFileService files,
            ProductPublicationService publication,
            JdbcClient jdbc,
            Environment environment) {
        DemoAccounts.refuseInProd(environment);
        this.products = products;
        this.covers = covers;
        this.files = files;
        this.publication = publication;
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        UUID administratorId = jdbc.sql("select id from administrators order by created_at limit 1")
                .query(UUID.class)
                .optional()
                .orElse(null);
        if (administratorId == null) {
            log.warn("demo data: no administrator, products left as they are");
            return;
        }
        for (UUID id : DEMO_IDS) {
            ProductResponse product = products.find(id).orElse(null);
            if (product == null || product.status() != ProductStatus.DRAFT || product.file() != null) {
                log.info("demo data: product skipped");
                continue;
            }
            publishWithFiles(product, administratorId);
            log.info("demo data: product published");
        }
    }

    private void publishWithFiles(ProductResponse product, UUID administratorId) throws IOException {
        Path cover = Files.createTempFile("demo-cover-", ".png");
        Path pdf = Files.createTempFile("demo-file-", ".pdf");
        try {
            Files.write(cover, coverPng(product.category()));
            Files.write(pdf, pdf(product.title()));
            covers.upload(product.id(), cover, administratorId, ORIGIN);
            files.upload(product.id(), pdf, "demo.pdf", "application/pdf", administratorId, ORIGIN, true);
            publication.publish(product.id(), administratorId, ORIGIN);
        } finally {
            Files.deleteIfExists(cover);
            Files.deleteIfExists(pdf);
        }
    }

    /** A plain PNG of colored bands by category, with no text, so it needs no fonts (CI has no fontconfig). */
    static byte[] coverPng(ProductCategory category) {
        Color[] palette = palette(category);
        BufferedImage image = new BufferedImage(COVER_WIDTH, COVER_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            int bandHeight = COVER_HEIGHT / palette.length;
            for (int i = 0; i < palette.length; i++) {
                g.setColor(palette[i]);
                int y = i * bandHeight;
                int height = i == palette.length - 1 ? COVER_HEIGHT - y : bandHeight;
                g.fillRect(0, y, COVER_WIDTH, height);
            }
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(image, "png", out)) {
                throw new IllegalStateException("no PNG writer available");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static Color[] palette(ProductCategory category) {
        return switch (category) {
            case HEALTH -> new Color[] {new Color(0x2E7D32), new Color(0x66BB6A), new Color(0xC8E6C9)};
            case LAW -> new Color[] {new Color(0x4E342E), new Color(0x8D6E63), new Color(0xD7CCC8)};
            case FINANCE -> new Color[] {new Color(0x0D47A1), new Color(0x42A5F5), new Color(0xBBDEFB)};
            case SECURITY -> new Color[] {new Color(0x263238), new Color(0x546E7A), new Color(0xCFD8DC)};
            case EDUCATION -> new Color[] {new Color(0xE65100), new Color(0xFFA726), new Color(0xFFE0B2)};
            case TECHNOLOGY -> new Color[] {new Color(0x4A148C), new Color(0xAB47BC), new Color(0xE1BEE7)};
            case BUSINESS -> new Color[] {new Color(0x880E4F), new Color(0xEC407A), new Color(0xF8BBD0)};
            case OTHER -> new Color[] {new Color(0x37474F), new Color(0x90A4AE), new Color(0xECEFF1)};
        };
    }

    /** A two-page PDF with the Helvetica standard font (no font file needed). */
    static byte[] pdf(String title) {
        try (PDDocument document = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            addPage(document, font, title);
            addPage(document, font, "Conteúdo de demonstração gerado pelo sistema. Não é um e-book de verdade.");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void addPage(PDDocument document, PDType1Font font, String text) throws IOException {
        PDPage page = new PDPage();
        document.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            content.beginText();
            content.setFont(font, 14);
            content.newLineAtOffset(60, 700);
            content.showText(text);
            content.endText();
        }
    }
}
