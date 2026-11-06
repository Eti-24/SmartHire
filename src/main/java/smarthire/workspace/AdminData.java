package smarthire.workspace;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import smarthire.documents.StoredDocument;

public final class AdminData {
    private AdminData() { }

    public enum Kind {
        STUDENT("Student accounts"), COMPANY("Company accounts"), JOB("Job postings"), CV("CV versions");

        private final String label;
        Kind(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    public record Target(Kind kind, String id) { }
    public record Entry(Target target, String name, String detail) { }
    public record Impact(Entry entry, long jobs, long versions, long applications, long originals, long closures, long bookmarks,
            long interviewEvents, long interviewNotices, long recruiterNotes, long interviewFeedback, long additionalOpenVacancies) { }
    public record Original(UUID id, StoredDocument.Format format) { }
    public record Table(String name, List<String> columns, List<List<String>> rows) { }
    public record ExportResult(int unavailableOriginals, boolean auditOutcomeRecorded) {
        public ExportResult(int unavailableOriginals) { this(unavailableOriginals, true); }
    }
    public record EraseResult(int filesPendingCleanup, boolean auditOutcomeRecorded) { }

    public interface Store {
        List<Entry> list(Kind kind);
        Impact impact(Target target);
        List<Original> export(Target target, Consumer<Table> writeTable);
        List<Original> erase(Impact confirmed);
    }
}
