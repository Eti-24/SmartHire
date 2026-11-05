package smarthire.matching;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import smarthire.cv.CvProfile;
import smarthire.cv.CvService;
import smarthire.documents.ManagedDocuments;
import smarthire.identity.*;
import smarthire.jobs.Job;
import smarthire.jobs.JobService;
import smarthire.skills.Skill;
import smarthire.storage.*;
import static org.junit.jupiter.api.Assertions.*;
import static smarthire.jobs.Job.*;

@EnabledIfSystemProperty(named = "smarthire.integration", matches = "true")
class MatchIntegrationTest {
    @TempDir Path root;
    private final Database database = Database.load(Path.of(".local/application.properties"), true);
    private final List<Long> accounts = new ArrayList<>();
    private IdentityService identity;
    private JobService jobs;
    private CvService cvs;
    private MatchService service;
    private ManagedDocuments documents;
    private UUID student, other, company, admin;
    private long companyId, category;
    private String skill, missing;

    @BeforeEach void setup() {
        database.initialize();
        var repository = new JdbcAccounts(database); identity = new IdentityService(repository);
        String hash = Passwords.hash("Match-test-password!21".toCharArray());
        admin = account(repository, Role.ADMIN, hash); company = account(repository, Role.COMPANY, hash);
        student = account(repository, Role.STUDENT, hash); other = account(repository, Role.STUDENT, hash);
        companyId = identity.profile(company).id();
        jobs = new JobService(identity, new JdbcJobs(database));
        category = jobs.saveCategory(admin, 0, "Match test " + UUID.randomUUID());
        skill = "match-" + UUID.randomUUID(); missing = "match-" + UUID.randomUUID();
        jobs.saveSkill(admin, new Skill(skill, skill, Skill.Type.TECHNICAL, Set.of("alias-" + skill)));
        jobs.saveSkill(admin, new Skill(missing, missing, Skill.Type.SOFT, Set.of()));
        jobs.verify(admin, companyId, true);
        documents = new ManagedDocuments(root, new JdbcDocumentReferences(database));
        cvs = new CvService(identity, new JdbcCvs(database), documents);
        service = new MatchService(identity, cvs, jobs);
    }

    private UUID account(JdbcAccounts repository, Role role, String hash) {
        var account = repository.create(UUID.randomUUID() + "@match.example", hash, role, "Fictional Match Test");
        accounts.add(account.id());
        return identity.login(account.email(), "Match-test-password!21".toCharArray());
    }

    private Details details(String requiredSkill, boolean mandatory) {
        return new Details("Fictional matching job", "Test description", category, "Dhaka", BigDecimal.ZERO, BigDecimal.TEN,
                List.of(new Requirement(Kind.SKILL, requiredSkill, 0, Priority.REQUIRED, mandatory)));
    }

    private long publish(Details details) {
        long id = jobs.submit(company, 0, details); jobs.moderate(admin, id, true, ""); return id;
    }

    private CvProfile confirm(List<String> skills) {
        var qualifications = new CvProfile.Qualifications(skills, null, "", List.of(), List.of(), List.of());
        try (var draft = cvs.current(student).isEmpty()
                ? cvs.upload(student, Path.of("src/test/resources/documents/fictional-cv.pdf")) : cvs.edit(student)) {
            return cvs.confirm(student, draft, qualifications);
        }
    }

    private List<MatchService.Recommendation> ownedMatches() {
        return service.recommend(student).jobs().stream().filter(r -> r.job().companyId() == companyId).toList();
    }

    @Test void noReviewPromptsReviewAndRankingsUseEligibilityThenUnroundedScoreThenStableId() {
        long first = publish(details("alias-" + skill, true));
        long second = publish(details(skill, true));
        long lowerEligible = publish(details(missing, false));
        long ineligible = publish(new Details("High but ineligible", "Test description", category, "Dhaka", BigDecimal.ZERO, BigDecimal.TEN,
                List.of(new Requirement(Kind.SKILL, skill, 0, Priority.REQUIRED, false),
                        new Requirement(Kind.SKILL, missing, 0, Priority.OPTIONAL, true))));
        assertTrue(service.recommend(student).needsReview());
        assertTrue(service.recommend(student).jobs().isEmpty());
        assertThrows(UserError.class, () -> service.open(student, first));
        CvProfile saved = confirm(List.of("alias-" + skill));
        var ranked = ownedMatches();
        assertEquals(List.of(first, second, lowerEligible, ineligible), ranked.stream().map(r -> r.job().id()).toList());
        assertEquals(saved.id(), ranked.getFirst().cvVersion());
        assertEquals(100, ranked.getFirst().match().percentage());
        assertEquals(0, ranked.get(2).match().percentage());
        assertFalse(ranked.getLast().match().eligible());
        assertTrue(service.recommend(other).needsReview());
    }

    @Test void reopeningAndRefreshUseNewConfirmedCvAndReapprovedRequirementsAcrossServiceRestart() {
        long job = publish(details(skill, true));
        CvProfile initial = confirm(List.of(skill));
        var original = service.open(student, job);
        assertTrue(original.match().eligible());
        CvProfile changed = confirm(List.of());
        var refreshed = service.open(student, job);
        assertEquals(changed.id(), refreshed.cvVersion());
        assertFalse(refreshed.match().eligible());
        assertEquals(0, ownedMatches().getFirst().match().percentage());
        assertEquals(initial.id(), original.cvVersion());
        assertEquals(100, original.match().percentage());
        jobs.submit(company, job, details(missing, false));
        assertThrows(UserError.class, () -> service.open(student, job));
        assertTrue(ownedMatches().isEmpty());
        jobs.moderate(admin, job, true, "");
        var restarted = new MatchService(identity, new CvService(identity, new JdbcCvs(database), documents), new JobService(identity, new JdbcJobs(database)));
        assertTrue(restarted.open(student, job).match().eligible());
        assertEquals(changed.id(), restarted.open(student, job).cvVersion());
        jobs.close(company, job);
        assertThrows(UserError.class, () -> restarted.open(student, job));
        assertTrue(ownedMatches().isEmpty());
    }

    @Test void studentPermissionsAndLiveCompanyVisibilityAreEnforcedForPreviouslyDisplayedResults() throws Exception {
        long job = publish(details(skill, true)); confirm(List.of(skill));
        assertTrue(service.open(student, job).match().eligible());
        for (UUID forbidden : List.of(admin, company)) {
            assertThrows(UserError.class, () -> service.recommend(forbidden));
            assertThrows(UserError.class, () -> service.open(forbidden, job));
        }
        jobs.verify(admin, companyId, false);
        assertTrue(ownedMatches().isEmpty());
        assertThrows(UserError.class, () -> service.open(student, job));
        jobs.verify(admin, companyId, true);
        setActive(companyId, false);
        assertThrows(UserError.class, () -> service.open(student, job));
        assertTrue(ownedMatches().isEmpty());
        setActive(companyId, true);
        setActive(identity.profile(student).id(), false);
        assertThrows(UserError.class, () -> service.recommend(student));
        assertThrows(UserError.class, () -> service.open(student, job));
    }

    private void setActive(long id, boolean active) throws Exception {
        try (var connection = database.connect(); var update = connection.prepareStatement("UPDATE users SET active=? WHERE id=?")) {
            update.setBoolean(1, active); update.setLong(2, id); update.executeUpdate();
        }
    }

    @AfterEach void cleanup() throws Exception {
        if (documents != null) { documents.close(); }
        try (var connection = database.connect()) {
            for (long id : accounts) {
                execute(connection, "DELETE r FROM job_requirements r JOIN jobs j ON j.id=r.job_id WHERE j.company_id=?", id);
                execute(connection, "DELETE FROM jobs WHERE company_id=?", id);
                var documentIds = new ArrayList<String>();
                try (var query = connection.prepareStatement("SELECT DISTINCT document_id FROM cv_versions WHERE student_id=?")) {
                    query.setLong(1, id); try (var rows = query.executeQuery()) { while (rows.next()) { documentIds.add(rows.getString(1)); } }
                }
                execute(connection, "DELETE FROM cv_current WHERE student_id=?", id);
                for (String table : List.of("cv_evidence_skills", "cv_evidence", "cv_skills", "cv_certifications")) {
                    execute(connection, "DELETE c FROM " + table + " c JOIN cv_versions v ON v.id=c.version_id WHERE v.student_id=?", id);
                }
                execute(connection, "DELETE FROM cv_versions WHERE student_id=?", id);
                for (String document : documentIds) { execute(connection, "DELETE FROM managed_documents WHERE id=?", document); }
                execute(connection, "DELETE FROM students WHERE user_id=?", id);
                execute(connection, "DELETE FROM companies WHERE user_id=?", id);
                execute(connection, "DELETE FROM users WHERE id=?", id);
            }
            if (category != 0) { execute(connection, "DELETE FROM categories WHERE id=?", category); }
            for (String id : new String[]{skill, missing}) {
                if (id != null) { execute(connection, "DELETE FROM skill_aliases WHERE skill_id=?", id); execute(connection, "DELETE FROM skills WHERE id=?", id); }
            }
        }
    }

    private static void execute(java.sql.Connection connection, String sql, Object value) throws Exception {
        try (var statement = connection.prepareStatement(sql)) { statement.setObject(1, value); statement.executeUpdate(); }
    }
}
