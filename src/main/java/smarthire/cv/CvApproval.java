package smarthire.cv;

import java.time.Instant;

/** Approval belongs to one immutable confirmed version, independently of job eligibility. */
public record CvApproval(Status status, String reason, Long reviewerId, Instant decidedAt) {
    public enum Status { PENDING, APPROVED, REJECTED }
    public static CvApproval pending() { return new CvApproval(Status.PENDING, "", null, null); }
    public String message() {
        return switch (status) {
            case PENDING -> "Awaiting admin approval. You can preview matches, but cannot apply yet.";
            case APPROVED -> "Approved by admin. You may apply to jobs whose mandatory requirements you meet.";
            case REJECTED -> "Rejected: " + reason + "\nEdit your review or upload a corrected CV and confirm it for another review.";
        };
    }
}
