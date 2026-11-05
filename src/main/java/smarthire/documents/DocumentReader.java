package smarthire.documents;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import static smarthire.documents.DocumentFailure.Code.*;

/** Shared conservative text reader. English text is supported; OCR and language inference are excluded. */
public final class DocumentReader {
    public static final int MAX_TEXT_CHARS = 500_000;
    static {
        ZipSecureFile.setMaxEntrySize(20L * 1024 * 1024);
        ZipSecureFile.setMaxTextSize(MAX_TEXT_CHARS);
        ZipSecureFile.setMaxFileCount(1000);
    }

    public String read(Path file, StoredDocument.Format format) {
        try {
            String text;
            if (format == StoredDocument.Format.PDF) {
                try (var pdf = Loader.loadPDF(file.toFile())) {
                    if (pdf.isEncrypted()) { throw new DocumentFailure(ENCRYPTED, "Password-protected PDFs are not supported. Export an unencrypted copy."); }
                    LimitedWriter writer = new LimitedWriter();
                    new PDFTextStripper().writeText(pdf, writer);
                    text = writer.toString();
                }
            } else {
                try (var input = java.nio.file.Files.newInputStream(file); var document = new XWPFDocument(input);
                        var extractor = new XWPFWordExtractor(document)) {
                    if (!document.getPackagePart().getContentType().equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml")) {
                        throw new DocumentFailure(UNSUPPORTED, "Use a standard DOCX document without macros.");
                    }
                    text = extractor.getText();
                }
            }
            if (text.length() > MAX_TEXT_CHARS) { throw textLimit(); }
            if (text.isBlank()) { throw new DocumentFailure(NO_TEXT, "No readable text was found. Image-only documents require OCR, which SmartHire does not support."); }
            return text.strip();
        } catch (InvalidPasswordException | EncryptedDocumentException exception) {
            throw new DocumentFailure(ENCRYPTED, "Encrypted documents are not supported. Export an unencrypted PDF or DOCX.");
        } catch (DocumentFailure exception) { throw exception; }
        catch (IOException | RuntimeException exception) {
            throw new DocumentFailure(MALFORMED, "This file could not be read as a valid PDF or DOCX. Export a new text-based copy.");
        }
    }

    private static DocumentFailure textLimit() { return new DocumentFailure(TOO_LARGE, "The document contains too much text. Use at most 500,000 characters."); }
    private static final class LimitedWriter extends Writer {
        private final StringBuilder text = new StringBuilder();
        @Override public void write(char[] chars, int offset, int length) {
            if (text.length() + length > MAX_TEXT_CHARS) { throw textLimit(); }
            text.append(chars, offset, length);
        }
        @Override public void flush() { }
        @Override public void close() { }
        @Override public String toString() { return text.toString(); }
    }
}
