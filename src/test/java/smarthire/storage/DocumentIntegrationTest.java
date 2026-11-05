package smarthire.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import smarthire.documents.ManagedDocuments;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "smarthire.integration", matches = "true")
class DocumentIntegrationTest {
    @TempDir Path root;

    @Test void commitsReferencesAndRollsBackWithoutLosingAnEarlierVersion() throws Exception {
        Database database = Database.load(Path.of(".local/application.properties"), true); database.initialize();
        JdbcDocumentReferences references = new JdbcDocumentReferences(database);
        List<UUID> ids = new ArrayList<>();
        UUID original;
        try {
            try (var store = new ManagedDocuments(root, references)) {
                try (var pending = store.prepare(Path.of("src/test/resources/documents/fictional-cv.pdf"))) {
                    ids.add(pending.document().id()); original = store.commit(pending).id();
                }
                UUID replacement;
                try (var pending = store.prepare(Path.of("src/test/resources/documents/fictional-cv.docx"))) {
                    replacement = pending.document().id(); ids.add(replacement); pending.validate();
                    try (var connection = database.connect()) {
                        connection.setAutoCommit(false); JdbcDocumentReferences.insert(connection, pending.document()); connection.rollback();
                    }
                }
                assertTrue(references.find(replacement).isEmpty());
                assertFalse(Files.exists(root.resolve(replacement + ".docx")));
                assertTrue(references.find(original).isPresent());
                assertArrayEquals(Files.readAllBytes(Path.of("src/test/resources/documents/fictional-cv.pdf")), store.read(original));
                // Simulate a larger owning transaction committing before the pending handle closes.
                try (var pending = store.prepare(Path.of("src/test/resources/documents/fictional-cv.docx"))) {
                    ids.add(pending.document().id()); pending.validate();
                    try (var connection = database.connect()) {
                        connection.setAutoCommit(false); JdbcDocumentReferences.insert(connection, pending.document()); connection.commit();
                    }
                }
            }
            try (var restarted = new ManagedDocuments(root, new JdbcDocumentReferences(database))) {
                var report = restarted.reconcile();
                assertFalse(report.unavailableReferences().contains(original));
                assertTrue(new JdbcDocumentReferences(database).find(original).orElseThrow().text().contains("Avery"));
                assertArrayEquals(Files.readAllBytes(Path.of("src/test/resources/documents/fictional-cv.pdf")), restarted.read(original));
            }
        } finally {
            try (var connection = database.connect(); var statement = connection.prepareStatement("DELETE FROM managed_documents WHERE id=?")) {
                for (UUID id : ids) { statement.setString(1, id.toString()); statement.executeUpdate(); }
            }
        }
    }
}
