package smarthire.applications;

import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

public record Interview(UUID applicationId, int revision, Status status, Slot slot, Instant changedAt,
        String actor, String reason) {
    public enum Status { PROPOSED, CONFIRMED, DECLINED, RESCHEDULE_REQUESTED, CANCELLED }
    public enum Action { PROPOSE, ACCEPT, DECLINE, RESCHEDULE, CANCEL }

    public record Slot(Instant start, Instant end, ZoneId zone, String location) {
        public boolean overlaps(Slot other) {
            return start.isBefore(other.end) && other.start.isBefore(end);
        }
    }

    public boolean canCancel(Instant now) {
        return status == Status.RESCHEDULE_REQUESTED
                || (status == Status.PROPOSED || status == Status.CONFIRMED) && slot.start.isAfter(now);
    }

    public boolean inProgress(Instant now) {
        return status == Status.CONFIRMED && !slot.start.isAfter(now) && slot.end.isAfter(now);
    }
}
