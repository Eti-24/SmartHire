package smarthire.jobs;

import java.math.BigDecimal;
import java.util.List;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import smarthire.identity.UserError;

public record Job(long id, long companyId, String companyName, Details details, Status status, String decision, long hiredCount) {
    public long remainingVacancies() { return Math.max(0, details.vacancyCount() - hiredCount); }
    public boolean accepting(Company company, Instant now) {
        return status == Status.APPROVED && company.active() && company.verified()
                && remainingVacancies() > 0 && !details.expired(now);
    }

    public enum Status { PENDING, APPROVED, REJECTED, CLOSED }
    public enum Kind { SKILL, DEGREE, FIELD, EXPERIENCE, EXPERIENCE_TAG, PROJECT, CERTIFICATION }
    public enum Priority { REQUIRED, OPTIONAL }
    public enum Degree { SECONDARY, DIPLOMA, BACHELOR, MASTER, DOCTORATE }

    public record Requirement(Kind kind, String value, int months, Priority priority, boolean mandatory) { }
    public record Details(String title, String description, long categoryId, String location,
            BigDecimal salaryMin, BigDecimal salaryMax, List<Requirement> requirements, int vacancyCount, Deadline deadline) {
        public Details { requirements = List.copyOf(requirements); }
        public Details(String title, String description, long categoryId, String location,
                BigDecimal salaryMin, BigDecimal salaryMax, List<Requirement> requirements, int vacancyCount) {
            this(title, description, categoryId, location, salaryMin, salaryMax, requirements, vacancyCount, null);
        }
        public Details(String title, String description, long categoryId, String location,
                BigDecimal salaryMin, BigDecimal salaryMax, List<Requirement> requirements) {
            this(title, description, categoryId, location, salaryMin, salaryMax, requirements, 1);
        }
        public boolean expired(Instant now) { return deadline != null && !now.isBefore(deadline.time()); }
    }
    public record Deadline(Instant time, ZoneId zone) {
        public Deadline {
            if (time == null || zone == null || zone.getId().length() > 80
                    || time.isBefore(Instant.parse("1000-01-02T00:00:00Z"))
                    || !time.isBefore(Instant.parse("9999-12-31T00:00:00Z"))) {
                throw new UserError("Enter a supported deadline and time zone.");
            }
            time = time.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        }
        public static Deadline of(LocalDateTime time, ZoneId zone) {
            var offsets = zone.getRules().getValidOffsets(time);
            if (offsets.size() != 1) { throw new UserError("This time is skipped or repeated in the chosen zone. Choose another time."); }
            return new Deadline(time.toInstant(offsets.getFirst()), zone);
        }
        @Override public String toString() { return time.atZone(zone).toLocalDateTime() + " " + zone; }
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
