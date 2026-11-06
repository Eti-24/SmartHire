package smarthire.applications;

import java.time.Instant;
import java.util.List;

public record ApplicationTimeline(Application.Status currentStatus, List<Event> events) {
    public record Event(Instant time, String label) {}
}
