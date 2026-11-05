package smarthire.jobs;

import java.math.BigDecimal;
import java.util.List;

public record Job(long id, long companyId, String companyName, Details details, Status status, String decision) {
    public enum Status { PENDING, APPROVED, REJECTED, CLOSED }
    public enum Kind { SKILL, DEGREE, FIELD, EXPERIENCE, EXPERIENCE_TAG, PROJECT, CERTIFICATION }
    public enum Priority { REQUIRED, OPTIONAL }
    public enum Degree { SECONDARY, DIPLOMA, BACHELOR, MASTER, DOCTORATE }

    public record Requirement(Kind kind, String value, int months, Priority priority, boolean mandatory) { }
    public record Details(String title, String description, long categoryId, String location,
            BigDecimal salaryMin, BigDecimal salaryMax, List<Requirement> requirements) {
        public Details { requirements = List.copyOf(requirements); }
    }
    public record Category(long id, String name) {
        @Override public String toString() { return name; }
    }
    public record Company(long id, String name, boolean active, boolean verified) {
        @Override public String toString() { return name + (verified ? " (verified)" : " (unverified)"); }
    }
}
