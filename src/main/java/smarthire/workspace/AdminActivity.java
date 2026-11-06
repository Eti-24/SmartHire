package smarthire.workspace;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AdminActivity {
    private AdminActivity() { }

    public enum Action { CV_DECISION, COMPANY_VERIFICATION, JOB_MODERATION, ACCOUNT_ACCESS, EXPORT, ERASE }
    public record Change(Action action, String targetKind, String targetId, String outcome, Long count, UUID operationId) {
        public Change(Action action, String targetKind, String targetId, String outcome) {
            this(action, targetKind, targetId, outcome, null, null);
        }
    }
    public record Entry(long id, Instant time, long adminId, Change change) { }
    public record Filter(Action action, String targetId, Instant from, Instant until, Long beforeId) { }
    public record Page(List<Entry> entries, Long nextBeforeId) { }
}
