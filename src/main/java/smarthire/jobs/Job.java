package smarthire.jobs;

import java.math.BigDecimal;
import java.util.List;

public record Job(long id, long companyId, String companyName, Details details, Status status, String decision, long hiredCount) {
    public long remainingVacancies() { return Math.max(0, details.vacancyCount() - hiredCount); }

    public enum Status { PENDING, APPROVED, REJECTED, CLOSED }
    public enum Kind { SKILL, DEGREE, FIELD, EXPERIENCE, EXPERIENCE_TAG, PROJECT, CERTIFICATION }
    public enum Priority { REQUIRED, OPTIONAL }
    public enum Degree { SECONDARY, DIPLOMA, BACHELOR, MASTER, DOCTORATE }

    public record Requirement(Kind kind, String value, int months, Priority priority, boolean mandatory) { }
    public record Details(String title, String description, long categoryId, String location,
            BigDecimal salaryMin, BigDecimal salaryMax, List<Requirement> requirements, int vacancyCount) {
        public Details { requirements = List.copyOf(requirements); }
        public Details(String title, String description, long categoryId, String location,
                BigDecimal salaryMin, BigDecimal salaryMax, List<Requirement> requirements) {
            this(title, description, categoryId, location, salaryMin, salaryMax, requirements, 1);
        }
    }
    public record Category(long id, String name) {
        @Override public String toString() { return name; }
    }
    public record HiringRecord(long jobId, String title, String company, String category, String location, int vacancyCount) { }
    public record CompanyHire(java.util.UUID applicationId, long jobId, String title, String applicant,
            java.time.Instant hiredAt, int vacancyCount, long hiredCount) { }
    public record Company(long id, String name, boolean active, boolean verified) {
        @Override public String toString() { return name + (verified ? " (verified)" : " (unverified)"); }
    }
}
