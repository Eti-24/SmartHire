package smarthire.applications;

import java.time.Instant;
import java.util.UUID;
import smarthire.identity.Profile;

/** Evidence references immutable CV versions; only status can change. */
public record Application(UUID id, long studentId, long jobId, UUID cvVersion, Instant submittedAt,
        Status status, Profile submittedProfile, String originalPolicy, double originalScore, String originalExplanation) {
    public enum Status { APPLIED, SHORTLISTED, HIRED, REJECTED, WITHDRAWN;
        public boolean undecided() { return this == APPLIED || this == SHORTLISTED; }
    }
}
