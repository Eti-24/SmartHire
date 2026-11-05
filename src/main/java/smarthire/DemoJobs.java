package smarthire;

import java.math.BigDecimal;
import java.util.List;
import smarthire.jobs.Job;
import smarthire.skills.SkillVocabulary;
import smarthire.storage.Database;
import smarthire.storage.JdbcAccounts;
import smarthire.storage.JdbcJobs;

/** Explicit, additive fictional fixtures for the publication demonstration. */
public final class DemoJobs {
    private DemoJobs() { }
    public static void provision(Database database) {
        long company = new JdbcAccounts(database).findByEmail("company@smarthire.example").orElseThrow().id();
        provision(database, company);
    }

    static void provision(Database database, long company) {
        new JdbcJobs(database).transaction(store -> {
            for (var skill : SkillVocabulary.demo().skills()) {
                if (store.skills().stream().noneMatch(s -> s.id().equals(skill.id()))) { store.saveSkill(skill); }
            }
            for (String name : List.of("Software", "Business")) {
                if (store.categories().stream().noneMatch(c -> c.name().equals(name))) { store.saveCategory(0, name); }
            }
            if (store.jobs().stream().noneMatch(j -> j.companyId() == company)) {
                store.verify(company, true);
                for (int i = 0; i < 4; i++) {
                    String category = i == 1 ? "Business" : "Software";
                    long categoryId = store.categories().stream().filter(c -> c.name().equals(category)).findFirst().orElseThrow().id();
                    var requirements = List.of(new Job.Requirement(Job.Kind.SKILL, i == 1 ? "communication" : "java", 0, Job.Priority.REQUIRED, true));
                    var details = new Job.Details("Graduate analyst " + (i + 1), "Fictional demonstration posting. Work with a supportive team.",
                            categoryId, "Dhaka", new BigDecimal("30000"), new BigDecimal("50000"), requirements);
                    Job.Status status = i < 2 ? Job.Status.APPROVED : i == 2 ? Job.Status.PENDING : Job.Status.CLOSED;
                    if (status == Job.Status.APPROVED) {
                        new smarthire.matching.MatchEngine().assess(smarthire.cv.CvProfile.Qualifications.empty(), details,
                                new SkillVocabulary(store.skills()), store.activePolicy());
                    }
                    long id = store.saveJob(company, 0, details);
                    store.status(id, status, "Fictional demonstration fixture");
                }
            }
            return null;
        });
    }
}
