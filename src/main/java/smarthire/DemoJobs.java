package smarthire;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import smarthire.cv.CvProfile;
import smarthire.identity.Role;
import smarthire.jobs.Job;
import smarthire.jobs.JobRepository;
import smarthire.matching.MatchEngine;
import smarthire.skills.SkillVocabulary;
import smarthire.storage.Database;
import smarthire.storage.JdbcAccounts;
import smarthire.storage.JdbcJobs;

/** Explicit, additive fictional fixtures for the publication demonstration. */
public final class DemoJobs {
    private record Fixture(String title, String description, String category, String location,
            String mandatorySkill, int vacancies, Job.Status status) { }

    private DemoJobs() { }

    public static void provision(Database database) {
        var accounts = new JdbcAccounts(database);
        provision(database, Map.of(
                companyId(accounts, "company@smarthire.example"), originalJobs(),
                companyId(accounts, DemoSeed.SOFTWARE_COMPANY), List.of(
                        new Fixture("Junior Java Developer", "Build and test Java features with a mentor.",
                                "Software", "Dhaka", "java", 2, Job.Status.APPROVED),
                        new Fixture("Junior Web QA Analyst", "Test browser workflows and reproduce JavaScript defects.",
                                "Software", "Dhaka", "javascript", 1, Job.Status.APPROVED)),
                companyId(accounts, DemoSeed.BUSINESS_COMPANY), List.of(
                        new Fixture("Graduate Operations Associate", "Coordinate customer handoffs and prepare weekly operations reports.",
                                "Business", "Chattogram", "communication", 1, Job.Status.APPROVED),
                        new Fixture("Junior Reporting Analyst", "Use SQL to check and summarize service records.",
                                "Business", "Chattogram", "sql", 1, Job.Status.PENDING))));
    }

    static void provision(Database database, long company) {
        provision(database, Map.of(company, originalJobs()));
    }

    private static long companyId(JdbcAccounts accounts, String email) {
        return accounts.findByEmail(email).filter(account -> account.role() == Role.COMPANY).orElseThrow().id();
    }

    private static List<Fixture> originalJobs() {
        String description = "Work with a supportive team.";
        return List.of(
                new Fixture("Graduate analyst 1", description, "Software", "Dhaka", "java", 1, Job.Status.APPROVED),
                new Fixture("Graduate analyst 2", description, "Business", "Dhaka", "communication", 1, Job.Status.APPROVED),
                new Fixture("Graduate analyst 3", description, "Software", "Dhaka", "java", 1, Job.Status.PENDING),
                new Fixture("Graduate analyst 4", description, "Software", "Dhaka", "java", 1, Job.Status.CLOSED));
    }

    private static void provision(Database database, Map<Long, List<Fixture>> jobsByCompany) {
        new JdbcJobs(database).transaction(store -> {
            for (var skill : SkillVocabulary.demo().skills()) {
                if (store.skills().stream().noneMatch(s -> s.id().equals(skill.id()))) { store.saveSkill(skill); }
            }
            for (String name : List.of("Software", "Business")) {
                if (store.categories().stream().noneMatch(c -> c.name().equals(name))) { store.saveCategory(0, name); }
            }
            var existingJobs = store.jobs();
            for (var entry : jobsByCompany.entrySet()) {
                long company = entry.getKey();
                if (!store.active(company) || existingJobs.stream().anyMatch(job -> job.companyId() == company)) {
                    continue;
                }
                store.verify(company, true);
                for (Fixture fixture : entry.getValue()) { seedJob(store, company, fixture); }
            }
            return null;
        });
    }

    private static void seedJob(JobRepository.Store store, long company, Fixture fixture) {
        long category = store.categories().stream().filter(c -> c.name().equals(fixture.category()))
                .findFirst().orElseThrow().id();
        var requirements = List.of(new Job.Requirement(Job.Kind.SKILL, fixture.mandatorySkill(), 0, Job.Priority.REQUIRED, true));
        var details = new Job.Details(fixture.title(), "Fictional demonstration posting. " + fixture.description(),
                category, fixture.location(), BigDecimal.valueOf(30000), BigDecimal.valueOf(50000), requirements, fixture.vacancies());
        if (fixture.status() == Job.Status.APPROVED) {
            new MatchEngine().assess(CvProfile.Qualifications.empty(), details,
                    new SkillVocabulary(store.skills()), store.activePolicy());
        }
        long id = store.saveJob(company, 0, details);
        store.status(id, fixture.status(), "Fictional demonstration fixture");
    }
}
