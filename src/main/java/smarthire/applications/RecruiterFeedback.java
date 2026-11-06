package smarthire.applications;

import java.time.Instant;
import java.util.UUID;

public record RecruiterFeedback(int revision, String notes, Scorecard scorecard, Instant updatedAt) {
    public record Scorecard(int interviewRevision, Integer technical, Integer communication, Integer roleFit, String comments) { }
    public record Review(UUID applicationId, String applicant, RecruiterFeedback feedback, Integer completedInterviewRevision) { }
    public record Candidate(UUID applicationId, String applicant) {
        @Override public String toString() { return applicant + " · " + applicationId.toString().substring(0, 8); }
    }
}
