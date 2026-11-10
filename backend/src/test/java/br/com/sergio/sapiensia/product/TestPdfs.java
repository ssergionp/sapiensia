package br.com.sergio.sapiensia.product;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.filespecification.PDSimpleFileSpecification;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.interactive.action.PDAction;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionLaunch;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionRemoteGoTo;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;

/** Builds PDFs in memory for the tests; nothing is ever read from a committed file. */
final class TestPdfs {

    private TestPdfs() {}

    /** A valid PDF with {@code n} blank pages. */
    static byte[] pages(int n) {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < n; i++) {
                doc.addPage(new PDPage(PDRectangle.A4));
            }
            return save(doc);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A PDF with no page at all. */
    static byte[] zeroPages() {
        return pages(0);
    }

    /** A PDF whose root /Pages node says {@code declaredCount} but really holds {@code realPages}. */
    static byte[] lyingCount(int realPages, int declaredCount) {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < realPages; i++) {
                doc.addPage(new PDPage(PDRectangle.A4));
            }
            doc.getDocumentCatalog()
                    .getCOSObject()
                    .getCOSDictionary(COSName.PAGES)
                    .setInt(COSName.COUNT, declaredCount);
            return save(doc);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A PDF whose page tree loops: root -> intermediate node -> back to root (an ancestor). */
    static byte[] pageTreeCycle() {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(PDRectangle.A4));
            var root = doc.getDocumentCatalog().getCOSObject().getCOSDictionary(COSName.PAGES);
            var middle = new COSDictionary();
            middle.setItem(COSName.TYPE, COSName.PAGES);
            middle.setItem(COSName.PARENT, root);
            COSArray middleKids = new COSArray();
            middleKids.add(root);
            middle.setItem(COSName.KIDS, middleKids);
            middle.setInt(COSName.COUNT, 1);
            root.getCOSArray(COSName.KIDS).add(middle);
            return save(doc);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Needs a password to open (user password set). */
    static byte[] encryptedWithUserPassword() {
        return encrypted("owner-secret", "user-secret");
    }

    /** Opens without a password but is protected by an owner password only. */
    static byte[] encryptedWithOwnerPasswordOnly() {
        return encrypted("owner-secret", "");
    }

    /** One page with /URI, /GoToR, /Launch and JavaScript pointing at 127.0.0.1:{@code port}. */
    static byte[] withActiveContent(int port) {
        String url = "http://127.0.0.1:" + port + "/";
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            PDActionURI uri = new PDActionURI();
            uri.setURI(url);
            PDActionRemoteGoTo remote = new PDActionRemoteGoTo();
            remote.setFile(new PDSimpleFileSpecification(new COSString(url + "remote.pdf")));
            PDActionLaunch launch = new PDActionLaunch();
            launch.setFile(new PDSimpleFileSpecification(new COSString(url + "launch.exe")));
            PDActionJavaScript script = new PDActionJavaScript("app.launchURL('" + url + "js', true);");

            var actions = new PDAction[] {uri, remote, launch, script};
            for (var action : actions) {
                PDAnnotationLink link = new PDAnnotationLink();
                link.setRectangle(new PDRectangle(10, 10, 100, 20));
                link.setAction(action);
                page.getAnnotations().add(link);
            }
            doc.getDocumentCatalog().setOpenAction(script);
            // Uncompressed, so the test can see the action names in the bytes.
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out, CompressParameters.NO_COMPRESSION);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] encrypted(String ownerPassword, String userPassword) {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(PDRectangle.A4));
            StandardProtectionPolicy policy =
                    new StandardProtectionPolicy(ownerPassword, userPassword, new AccessPermission());
            policy.setEncryptionKeyLength(128);
            doc.protect(policy);
            return save(doc);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] save(PDDocument doc) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.save(out);
        return out.toByteArray();
    }
}
