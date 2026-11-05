package smarthire.documents;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static smarthire.documents.DocumentFailure.Code.*;

class ManagedDocumentsTest {
    @TempDir Path temporary;
    private final MemoryReferences references = new MemoryReferences();
    private Path fixture(String name) { return Path.of("src/test/resources/documents", name); }
    private Path storePath() { return temporary.resolve("store"); }

    @Test void readsRealPdfAndDocxAndPreservesOriginalBytesAfterSourceReplacement() throws Exception {
        for (String filename : new String[]{"fictional-cv.pdf", "fictional-cv.docx"}) {
            Path source = temporary.resolve(filename); Files.copy(fixture(filename), source);
            byte[] original = Files.readAllBytes(source);
            UUID id;
            try (var store = new ManagedDocuments(storePath(), references); var pending = store.prepare(source)) {
                assertTrue(pending.document().text().contains("Fictional Avery Student"));
                if (filename.endsWith("docx")) { assertTrue(pending.document().text().contains("Project evidence")); }
                id = store.commit(pending).id();
                Files.writeString(source, "Changed source");
                assertArrayEquals(original, store.read(id));
            }
            try (var restarted = new ManagedDocuments(storePath(), references)) {
                assertTrue(restarted.reconcile().unavailableReferences().isEmpty());
                assertArrayEquals(original, restarted.read(id));
            }
        }
    }

    @Test void typedValidationRejectsUnsupportedMalformedEncryptedTextlessAndOversizedFiles() throws Exception {
        try (var store = new ManagedDocuments(storePath(), references)) {
            assertCode(UNSUPPORTED, () -> store.prepare(temporary.resolve("photo.png")));
            Path malformed = temporary.resolve("fake.pdf"); Files.writeString(malformed, "not a pdf");
            assertCode(MALFORMED, () -> store.prepare(malformed));
            Path renamed = temporary.resolve("fake.docx"); Files.copy(fixture("fictional-cv.pdf"), renamed);
            assertCode(MALFORMED, () -> store.prepare(renamed));
            assertCode(ENCRYPTED, () -> store.prepare(fixture("encrypted.pdf")));
            assertCode(NO_TEXT, () -> store.prepare(fixture("textless.pdf")));
            assertCode(NO_TEXT, () -> store.prepare(fixture("textless.docx")));
            Path huge = temporary.resolve("huge.pdf");
            try (var output = new java.io.RandomAccessFile(huge.toFile(), "rw")) { output.setLength(ManagedDocuments.MAX_BYTES + 1); }
            assertCode(TOO_LARGE, () -> store.prepare(huge));
            assertCode(STORAGE, () -> store.prepare(temporary.resolve("missing.pdf")));
            try (var files = Files.list(storePath())) { assertEquals(1, files.count()); } // Store lock only.
        }
    }

    @Test void interruptedCopyRemovesItsPartialFile() throws Exception {
        ManagedDocuments.InputOpener broken = path -> new InputStream() {
            private int reads;
            @Override public int read() throws IOException { throw new IOException("Interrupted copy"); }
            @Override public int read(byte[] buffer, int off, int len) throws IOException {
                if (reads++ == 0) { buffer[off] = 1; return 1; }
                throw new IOException("Interrupted copy");
            }
        };
        try (var store = new ManagedDocuments(storePath(), references, broken)) {
            assertCode(STORAGE, () -> store.prepare(fixture("fictional-cv.pdf")));
            try (var files = Files.list(storePath())) { assertEquals(1, files.count()); }
        }
    }

    @Test void copyLimitsAreEnforcedDuringStreamingAndCancellationPreservesInterruptFlag() {
        byte[] tooMany = new byte[(int) ManagedDocuments.MAX_BYTES + 1];
        try (var store = new ManagedDocuments(storePath(), references, path -> new ByteArrayInputStream(tooMany))) {
            assertCode(TOO_LARGE, () -> store.prepare(fixture("fictional-cv.pdf")));
        }
        try (var store = new ManagedDocuments(storePath(), references)) {
            Thread.currentThread().interrupt();
            try {
                assertCode(INTERRUPTED, () -> store.prepare(fixture("fictional-cv.pdf")));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
        }
    }

    @Test void rollbackDiscardsOnlyNewUnreferencedCopyAndKeepsPreviousVersion() throws Exception {
        try (var store = new ManagedDocuments(storePath(), references)) {
            StoredDocument old;
            try (var first = store.prepare(fixture("fictional-cv.pdf"))) { old = store.commit(first); }
            UUID replacement;
            try (var second = store.prepare(fixture("fictional-cv.docx"))) { replacement = second.document().id(); }
            assertFalse(Files.exists(storePath().resolve(replacement + ".docx")));
            assertArrayEquals(Files.readAllBytes(fixture("fictional-cv.pdf")), store.read(old.id()));
        }
    }

    @Test void recoveryRemovesAbandonedFilesButKeepsReferencesAndCurrentPreparations() throws Exception {
        UUID committed, abandoned;
        try (var store = new ManagedDocuments(storePath(), references)) {
            try (var first = store.prepare(fixture("fictional-cv.pdf"))) { committed = store.commit(first).id(); }
            var unfinished = store.prepare(fixture("fictional-cv.docx")); abandoned = unfinished.document().id();
            assertEquals(0, store.reconcile().removedAbandonedFiles());
        }
        Files.writeString(storePath().resolve(UUID.randomUUID() + ".part"), "partial copy");
        Files.writeString(storePath().resolve("leave-me.txt"), "not managed");
        try (var restarted = new ManagedDocuments(storePath(), references)) {
            assertEquals(2, restarted.reconcile().removedAbandonedFiles());
            assertFalse(Files.exists(storePath().resolve(abandoned + ".docx")));
            assertTrue(Files.exists(storePath().resolve("leave-me.txt")));
            Files.delete(storePath().resolve(committed + ".pdf"));
            assertEquals(java.util.List.of(committed), restarted.reconcile().unavailableReferences());
            assertTrue(references.find(committed).isPresent());
            assertCode(UNAVAILABLE, () -> restarted.read(committed));
        }
    }

    @Test void changedCommittedBytesAreUnavailableButNeverDeletedByRecovery() throws Exception {
        try (var store = new ManagedDocuments(storePath(), references); var pending = store.prepare(fixture("fictional-cv.pdf"))) {
            StoredDocument document = store.commit(pending);
            Path file = storePath().resolve(document.id() + ".pdf");
            byte[] bytes = Files.readAllBytes(file); bytes[bytes.length - 1] ^= 1; Files.write(file, bytes);
            assertCode(UNAVAILABLE, () -> store.read(document.id()));
            assertEquals(java.util.List.of(document.id()), store.reconcile().unavailableReferences());
            assertTrue(Files.exists(file));
        }
    }

    @Test void uncertainCommitAndDatabaseOutageNeverDeleteSavedBytes() throws Exception {
        try (var store = new ManagedDocuments(storePath(), references)) {
            var pending = store.prepare(fixture("fictional-cv.pdf"));
            references.failAfterCommit = true;
            assertCode(STORAGE, () -> store.commit(pending));
            pending.close(); // A reference exists despite the transport failure.
            assertTrue(Files.exists(storePath().resolve(pending.document().id() + ".pdf")));
            var other = store.prepare(fixture("fictional-cv.docx"));
            references.offline = true;
            assertCode(STORAGE, other::close);
            assertCode(STORAGE, store::reconcile);
            assertTrue(Files.exists(storePath().resolve(other.document().id() + ".docx")));
            references.offline = false; other.close();
        }
    }

    @Test void exclusiveStoreLockPreventsSecondProcessRecoveryFromDeletingActiveUploads() {
        try (var store = new ManagedDocuments(storePath(), references)) {
            assertCode(IN_USE, () -> new ManagedDocuments(storePath(), references));
        }
        try (var reopened = new ManagedDocuments(storePath(), references)) { assertEquals(0, reopened.reconcile().removedAbandonedFiles()); }
    }

    private static void assertCode(DocumentFailure.Code code, org.junit.jupiter.api.function.Executable work) { assertEquals(code, assertThrows(DocumentFailure.class, work).code()); }

    private static final class MemoryReferences implements DocumentReferences {
        private final Map<UUID, StoredDocument> documents = new HashMap<>();
        private boolean offline, failAfterCommit;
        private void check() { if (offline) { throw new DocumentFailure(STORAGE, "Offline"); } }
        public Map<UUID, StoredDocument.Format> committed() { check(); var result = new HashMap<UUID, StoredDocument.Format>(); documents.forEach((id, d) -> result.put(id, d.format())); return result; }
        public Optional<StoredDocument> find(UUID id) { check(); return Optional.ofNullable(documents.get(id)); }
        public void commit(StoredDocument document) {
            check(); documents.put(document.id(), document);
            if (failAfterCommit) { throw new DocumentFailure(STORAGE, "Commit response lost"); }
        }
    }
}
