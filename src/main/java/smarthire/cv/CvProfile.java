package smarthire.cv;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import smarthire.jobs.Job.Degree;

/** A confirmed version is immutable; edits create another version. */
public record CvProfile(UUID id, long studentId, UUID documentId, String originalName,
        String extractedText, Instant reviewedAt, Qualifications qualifications) {
    public record Version(UUID id, String originalName, Instant reviewedAt) { }
    public record Qualifications(List<String> skills, Degree degree, String field,
            List<Project> projects, List<Experience> experience, List<String> certifications) {
        public Qualifications {
            skills = List.copyOf(skills);
            projects = List.copyOf(projects);
            experience = List.copyOf(experience);
            certifications = List.copyOf(certifications);
        }
        public static Qualifications empty() {
            return new Qualifications(List.of(), null, "", List.of(), List.of(), List.of());
        }
    }
    public record Project(String evidence, List<String> skills) {
        public Project { skills = List.copyOf(skills); }
    }
    /** Ongoing roles store the review month as their fixed end, never a moving clock value. */
    public record Experience(String evidence, YearMonth start, YearMonth end, boolean ongoing, List<String> skills) {
        public Experience { skills = List.copyOf(skills); }
    }
}
