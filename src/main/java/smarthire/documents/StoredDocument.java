package smarthire.documents;

import java.util.UUID;

/** Immutable metadata; an ID always denotes exactly these original bytes and extracted text. */
public record StoredDocument(UUID id, Format format, String originalName, long size, String sha256, String text) {
    public enum Format { PDF, DOCX;
        public String extension() { return name().toLowerCase(java.util.Locale.ROOT); }
    }
}
