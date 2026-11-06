package smarthire.documents;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import static smarthire.documents.DocumentFailure.Code.*;

/** Single-process managed store. Use off the EDT; close pending copies on rollback. */
public final class ManagedDocuments implements AutoCloseable {
    public static final long MAX_BYTES = 5L * 1024 * 1024;
    private final Path root;
    private final DocumentReferences references;
    private final DocumentReader reader = new DocumentReader();
    private final Set<UUID> pending = new HashSet<>();
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final InputOpener opener;
    private boolean closed;

    @FunctionalInterface interface InputOpener { InputStream open(Path path) throws IOException; }

    public ManagedDocuments(Path root, DocumentReferences references) { this(root, references, Files::newInputStream); }

    ManagedDocuments(Path root, DocumentReferences references, InputOpener opener) {
        this.root = root.toAbsolutePath().normalize(); this.references = references; this.opener = opener;
        FileChannel channel = null;
        try {
            Files.createDirectories(this.root);
            if (Files.isSymbolicLink(this.root)) { throw new IOException("Symbolic store root"); }
            channel = FileChannel.open(this.root.resolve("store.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            FileLock acquired;
            try { acquired = channel.tryLock(); }
            catch (java.nio.channels.OverlappingFileLockException exception) { acquired = null; }
            if (acquired == null) { channel.close(); throw new DocumentFailure(IN_USE, "The document store is open in another SmartHire instance. Close that instance first."); }
            lockChannel = channel; lock = acquired;
        } catch (IOException exception) {
            if (channel != null) { try { channel.close(); } catch (IOException ignored) { } }
            throw storage();
        }
    }

    public synchronized Pending prepare(Path source) {
        checkOpen();
        if (source == null || source.getFileName() == null) { throw new DocumentFailure(UNSUPPORTED, "Choose a PDF or DOCX file."); }
        String name = source.getFileName().toString();
        String extension = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        StoredDocument.Format format = switch (extension) {
            case "pdf" -> StoredDocument.Format.PDF;
            case "docx" -> StoredDocument.Format.DOCX;
            default -> throw new DocumentFailure(UNSUPPORTED, "Only text-based PDF and DOCX files are supported.");
        };
        UUID id = UUID.randomUUID(); Path stage = root.resolve(id + ".part"); Path destination = path(id, format);
        try {
            if (!Files.isRegularFile(source) || !Files.isReadable(source)) { throw storage(); }
            if (Files.size(source) > MAX_BYTES) { throw tooLarge(); }
            long size = 0; MessageDigest digest = digest();
            try (InputStream input = opener.open(source); var output = Files.newOutputStream(stage, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) { throw new DocumentFailure(INTERRUPTED, "Document import was interrupted. Your previous document is unchanged."); }
                    size += count; if (size > MAX_BYTES) { throw tooLarge(); }
                    output.write(buffer, 0, count); digest.update(buffer, 0, count);
                }
            }
            String text = reader.read(stage, format);
            try (var data = FileChannel.open(stage, StandardOpenOption.WRITE)) { data.force(true); }
            Files.move(stage, destination, StandardCopyOption.ATOMIC_MOVE);
            StoredDocument document = new StoredDocument(id, format, name, size, HexFormat.of().formatHex(digest.digest()), text);
            pending.add(id);
            return new Pending(document);
        } catch (IOException exception) {
            cleanup(stage); throw storage();
        } catch (RuntimeException exception) {
            cleanup(stage); throw exception;
        }
    }

    /** Checks integrity before committing a standalone reference; later CV/job transactions use JdbcDocumentReferences.insert. */
    public synchronized StoredDocument commit(Pending staged) {
        checkOpen(); staged.requirePending(); verify(staged.document);
        references.commit(staged.document);
        pending.remove(staged.document.id()); staged.finished = true;
        return staged.document;
    }

    /** Call only behind the future CV/job service's role and ownership checks. */
    public synchronized byte[] read(UUID id) {
        checkOpen();
        StoredDocument document = references.find(id).orElseThrow(() -> new DocumentFailure(UNAVAILABLE, "This document reference is unavailable."));
        return verify(document);
    }

    /** Run once at startup before uploads; active preparations in this process are also protected. */
    public synchronized Recovery reconcile() {
        checkOpen();
        var committed = references.committed(); // If DB lookup fails, nothing is deleted.
        var unavailable = new ArrayList<UUID>(); int removed = 0;
        try (var files = Files.list(root)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (!name.matches("[0-9a-f-]{36}\\.(part|pdf|docx)")) { continue; }
                UUID id;
                try { id = UUID.fromString(name.substring(0, 36)); } catch (IllegalArgumentException e) { continue; }
                if (!committed.containsKey(id) && !pending.contains(id) && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    Files.delete(file); removed++;
                }
            }
        } catch (IOException exception) { throw storage(); }
        for (UUID id : committed.keySet()) {
            try { read(id); } catch (DocumentFailure exception) {
                if (exception.code() != UNAVAILABLE) { throw exception; }
                unavailable.add(id);
            }
        }
        return new Recovery(removed, List.copyOf(unavailable));
    }

    public record Recovery(int removedAbandonedFiles, List<UUID> unavailableReferences) { }

    /** Remove one generated file after its metadata was erased in a committed transaction. */
    public synchronized void removeUnreferenced(UUID id, StoredDocument.Format format) {
        checkOpen();
        Path file = path(id, format);
        if (!pending.contains(id) && references.find(id).isEmpty()
                && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            cleanup(file);
        }
    }

    public final class Pending implements AutoCloseable {
        private final StoredDocument document;
        private boolean finished;
        private Pending(StoredDocument document) { this.document = document; }
        public StoredDocument document() { return document; }
        /** Confirm the managed bytes before inserting metadata inside a larger application transaction. */
        public void validate() { synchronized (ManagedDocuments.this) { checkOpen(); requirePending(); verify(document); } }
        private void requirePending() {
            if (finished || !pending.contains(document.id())) { throw new DocumentFailure(STORAGE, "This prepared document is no longer pending."); }
        }
        @Override public void close() {
            synchronized (ManagedDocuments.this) {
                if (finished) { return; }
                checkOpen();
                // A failed/ambiguous DB commit must never result in deleting a committed file.
                if (references.find(document.id()).isEmpty()) { cleanup(path(document.id(), document.format())); }
                pending.remove(document.id()); finished = true;
            }
        }
    }

    private byte[] verify(StoredDocument document) {
        Path file = path(document.id(), document.format());
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) != document.size() || Files.size(file) > MAX_BYTES) { throw unavailable(); }
            byte[] bytes = Files.readAllBytes(file);
            if (!HexFormat.of().formatHex(digest().digest(bytes)).equals(document.sha256())) { throw unavailable(); }
            return bytes;
        } catch (IOException exception) { throw unavailable(); }
    }
    private Path path(UUID id, StoredDocument.Format format) { return root.resolve(id.toString() + "." + format.extension()); }
    private static MessageDigest digest() { try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
    private static DocumentFailure tooLarge() { return new DocumentFailure(TOO_LARGE, "Choose a PDF or DOCX no larger than 5 MB."); }
    private static DocumentFailure unavailable() { return new DocumentFailure(UNAVAILABLE, "The saved document is missing or changed. Its history is retained; restore the original file or upload a new version."); }
    private static DocumentFailure storage() { return new DocumentFailure(STORAGE, "The document could not be stored. Check file access and available disk space, then try again."); }
    private static void cleanup(Path path) { try { Files.deleteIfExists(path); } catch (IOException exception) { throw storage(); } }
    private void checkOpen() { if (closed) { throw new DocumentFailure(STORAGE, "The document store is closed."); } }
    @Override public synchronized void close() {
        if (closed) { return; }
        try { lock.release(); lockChannel.close(); closed = true; }
        catch (IOException exception) { throw storage(); }
    }
}
