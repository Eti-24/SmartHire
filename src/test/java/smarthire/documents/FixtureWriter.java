package smarthire.documents;

import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

/** Regenerates tiny fictional parser fixtures without personal data. Run explicitly, not during tests. */
public final class FixtureWriter {
    public static void main(String[] args) throws Exception {
        Path root = Path.of("src/test/resources/documents"); Files.createDirectories(root);
        try (var pdf = new PDDocument()) {
            var page = new PDPage(); pdf.addPage(page);
            try (var text = new PDPageContentStream(pdf, page)) {
                text.beginText(); text.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12); text.newLineAtOffset(40, 740);
                text.showText("Fictional Avery Student - Java, SQL and teamwork."); text.endText();
            }
            pdf.save(root.resolve("fictional-cv.pdf").toFile());
            var policy = new StandardProtectionPolicy("fixture-owner", "fixture-reader", new AccessPermission());
            policy.setEncryptionKeyLength(128); pdf.protect(policy); pdf.save(root.resolve("encrypted.pdf").toFile());
        }
        try (var pdf = new PDDocument()) { pdf.addPage(new PDPage()); pdf.save(root.resolve("textless.pdf").toFile()); }
        try (var document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("Fictional Avery Student - Java, SQL and teamwork.");
            var table = document.createTable(1, 1); table.getRow(0).getCell(0).setText("Project evidence: Java and communication.");
            try (var output = Files.newOutputStream(root.resolve("fictional-cv.docx"))) { document.write(output); }
        }
        try (var document = new XWPFDocument(); var output = Files.newOutputStream(root.resolve("textless.docx"))) { document.write(output); }
    }
}
