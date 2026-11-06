package smarthire.documents;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import smarthire.cv.CvExtractor;
import smarthire.jobs.RequirementExtractor;
import smarthire.skills.SkillVocabulary;
import static org.junit.jupiter.api.Assertions.*;

class DocumentTemplateTest {
    @TempDir Path folder;

    @Test void bundledTemplatesSaveByteForByteAndBlankPromptsYieldNoSuggestions() throws Exception {
        String cv = saveTemplate("SmartHire-CV-Template.docx");
        String circular = saveTemplate("SmartHire-Job-Circular-Template.docx");
        assertTrue(cv.contains("Projects"));
        assertTrue(circular.contains("Closing date"));
        var vocabulary = SkillVocabulary.demo();
        var suggestions = new CvExtractor().suggest(cv, vocabulary, YearMonth.of(2026, 11));
        assertNull(suggestions.degree());
        assertTrue(suggestions.skills().isEmpty());
        assertTrue(suggestions.projects().isEmpty());
        assertTrue(suggestions.experience().isEmpty());
        assertTrue(suggestions.certifications().isEmpty());
        assertTrue(new RequirementExtractor().suggest(circular, vocabulary).requirements().isEmpty());
    }

    @Test void fileExportPreservesExistingFileOnCollisionAndReadFailure() throws Exception {
        Path destination = FileExport.withExtension(folder.resolve("CV"), "docx");
        assertEquals(folder.resolve("CV.docx"), destination);
        assertEquals(folder.resolve("CV.DOCX"), FileExport.withExtension(folder.resolve("CV.DOCX"), "docx"));
        Files.writeString(destination, "existing");
        try (var source = template("SmartHire-CV-Template.docx")) {
            assertThrows(IOException.class, () -> FileExport.save(source, destination, false));
        }
        try (var broken = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("Failed mid-export"); }
        }) {
            assertThrows(IOException.class, () -> FileExport.save(broken, destination, true));
        }
        assertEquals("existing", Files.readString(destination));
        try (var files = Files.list(folder)) { assertEquals(1, files.count()); }
        try (var source = template("SmartHire-CV-Template.docx")) {
            FileExport.save(source, destination, true);
        }
        assertTrue(new DocumentReader().read(destination, StoredDocument.Format.DOCX).contains("Education"));
    }

    private String saveTemplate(String name) throws Exception {
        Path path = folder.resolve(name);
        try (var source = template(name)) { FileExport.save(source, path, false); }
        try (var source = template(name)) { assertArrayEquals(source.readAllBytes(), Files.readAllBytes(path)); }
        return new DocumentReader().read(path, StoredDocument.Format.DOCX);
    }

    private InputStream template(String name) {
        var source = getClass().getResourceAsStream("/templates/" + name);
        assertNotNull(source);
        return source;
    }
}
