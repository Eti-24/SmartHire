package smarthire.demo;

import java.math.BigDecimal;
import java.util.List;
import smarthire.jobs.Job;

/** The scripted assessment scenario, shared by fixture and clean-database checks. */
public final class DemoScenario {
    private DemoScenario() { }
    public static Job.Details job(long category) {
        return new Job.Details("Demo Java graduate developer", "Fictional graduate role. Java is mandatory; SQL, communication and teamwork contribute to the score.",
                category, "Dhaka", new BigDecimal("30000"), new BigDecimal("50000"), List.of(
                new Job.Requirement(Job.Kind.SKILL, "java", 0, Job.Priority.OPTIONAL, true),
                new Job.Requirement(Job.Kind.SKILL, "sql", 0, Job.Priority.REQUIRED, false),
                new Job.Requirement(Job.Kind.SKILL, "communication", 0, Job.Priority.REQUIRED, false),
                new Job.Requirement(Job.Kind.SKILL, "teamwork", 0, Job.Priority.REQUIRED, false),
                new Job.Requirement(Job.Kind.DEGREE, "BACHELOR", 0, Job.Priority.OPTIONAL, false)));
    }
}
