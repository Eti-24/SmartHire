package smarthire.jobs;

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
import smarthire.identity.*;
import smarthire.skills.Skill;
import smarthire.storage.*;
import static org.junit.jupiter.api.Assertions.*;
import static smarthire.jobs.Job.*;

@EnabledIfSystemProperty(named = "smarthire.integration", matches = "true")
class JobIntegrationTest {
    private final Database database = Database.load(Path.of(".local/application.properties"), true);
    private final List<Long> accounts = new ArrayList<>();
    private final List<Long> categories = new ArrayList<>();
    private final List<String> skillIds = new ArrayList<>();
    private IdentityService identity;
    private JobService service;
    private UUID admin, company, other, student;
    private long companyId, categoryA, categoryB;
    private String skillId;

    @BeforeEach void setup() {
        database.initialize();
        var repository = new JdbcAccounts(database); identity = new IdentityService(repository);
        String hash = Passwords.hash("Integration-test!21".toCharArray());
        admin = account(repository, Role.ADMIN, hash); company = account(repository, Role.COMPANY, hash);
        companyId = identity.profile(company).id(); other = account(repository, Role.COMPANY, hash); student = account(repository, Role.STUDENT, hash);
        service = new JobService(identity, new JdbcJobs(database));
        categoryA = service.saveCategory(admin, 0, "Test software " + UUID.randomUUID()); categories.add(categoryA);
        categoryB = service.saveCategory(admin, 0, "Test business " + UUID.randomUUID()); categories.add(categoryB);
        skillId = "test-" + UUID.randomUUID(); skillIds.add(skillId);
        service.saveSkill(admin, new Skill(skillId, skillId, Skill.Type.TECHNICAL, Set.of("alias-" + skillId)));
    }

    private UUID account(JdbcAccounts repository, Role role, String hash) {
        var account = repository.create(UUID.randomUUID() + "@jobs.example", hash, role, "Fictional Job Test"); accounts.add(account.id());
        return identity.login(account.email(), "Integration-test!21".toCharArray());
    }

    private Details details(long category) {
        return new Details("Graduate analyst", "Fictional description", category, "Dhaka", new BigDecimal("100"), new BigDecimal("200"),
                List.of(new Requirement(Kind.SKILL, skillId, 0, Priority.REQUIRED, true)));
    }

    @Test void publicationSearchOwnershipAndReapprovalSurviveNewConnections() {
        assertThrows(UserError.class, () -> service.submit(company, 0, details(categoryA)));
        assertThrows(UserError.class, () -> service.verify(company, companyId, true));
        service.verify(admin, companyId, true);
        long first = service.submit(company, 0, details(categoryA));
        long second = service.submit(company, 0, details(categoryB));
        long pending = service.submit(company, 0, details(categoryA));
        long closed = service.submit(company, 0, details(categoryA));
        service.moderate(admin, first, true, ""); service.moderate(admin, second, true, ""); service.moderate(admin, closed, true, ""); service.close(company, closed);
        assertEquals(List.of(first), service.list(student, "GRADUATE", categoryA).stream().map(Job::id).toList());
        assertEquals(2, service.list(student, "", null).stream().filter(j -> j.companyId() == companyId).count());
        assertTrue(service.list(student, "no-such-title", categoryA).isEmpty());
        assertThrows(UserError.class, () -> service.open(student, pending));
        assertThrows(UserError.class, () -> service.open(student, closed));
        assertThrows(UserError.class, () -> service.close(other, first));
        assertThrows(UserError.class, () -> service.submit(other, first, details(categoryA)));
        assertThrows(UserError.class, () -> service.moderate(student, pending, true, ""));
        assertThrows(UserError.class, () -> service.submit(company, closed, details(categoryA)));
        service.submit(company, first, details(categoryA));
        assertEquals(Status.PENDING, service.open(company, first).status());
        assertThrows(UserError.class, () -> service.open(student, first));
        service.moderate(admin, first, false, "Please clarify responsibilities");
        assertEquals("Please clarify responsibilities", service.open(company, first).decision());
        service.submit(company, first, details(categoryA)); service.moderate(admin, first, true, "");
        var restarted = new JobService(identity, new JdbcJobs(database));
        assertEquals(skillId, restarted.open(student, first).details().requirements().getFirst().value());
        service.verify(admin, companyId, false);
        assertThrows(UserError.class, () -> service.open(student, first));
        assertThrows(UserError.class, () -> service.moderate(admin, pending, true, ""));
        service.verify(admin, companyId, true);
        try (var connection = database.connect(); var update = connection.prepareStatement("UPDATE users SET active=FALSE WHERE id=?")) {
            update.setLong(1, companyId); update.executeUpdate();
        } catch (java.sql.SQLException e) { throw new AssertionError(e); }
        assertTrue(service.list(student, "", categoryA).stream().noneMatch(j -> j.companyId() == companyId));
        assertThrows(UserError.class, () -> service.close(company, first));
    }

    @Test void validatesStructuredRequirementsAndCatalogWithoutPartialWrites() {
        service.verify(admin, companyId, true);
        Details base = details(categoryA);
        List<List<Requirement>> invalid = List.of(
                List.of(),
                List.of(base.requirements().getFirst(), base.requirements().getFirst()),
                List.of(base.requirements().getFirst(), new Requirement(Kind.SKILL, "alias-" + skillId, 0, Priority.OPTIONAL, false)),
                List.of(new Requirement(Kind.CERTIFICATION, "Demo  Certificate", 0, Priority.REQUIRED, false), new Requirement(Kind.CERTIFICATION, "demo certificate", 0, Priority.REQUIRED, false)),
                List.of(new Requirement(Kind.SKILL, "missing-skill", 0, Priority.REQUIRED, false)),
                List.of(new Requirement(Kind.EXPERIENCE, "", -1, Priority.REQUIRED, false)),
                List.of(new Requirement(Kind.EXPERIENCE, "", 12, Priority.REQUIRED, true)),
                List.of(new Requirement(Kind.EXPERIENCE_TAG, skillId, 0, Priority.REQUIRED, false)),
                List.of(new Requirement(Kind.FIELD, "computer science", 0, Priority.REQUIRED, true)),
                List.of(new Requirement(Kind.DEGREE, "unknown", 0, Priority.REQUIRED, true)));
        for (var requirements : invalid) {
            assertThrows(UserError.class, () -> service.submit(company, 0, new Details(base.title(), base.description(), categoryA, base.location(), base.salaryMin(), base.salaryMax(), requirements)));
        }
        assertThrows(UserError.class, () -> service.submit(company, 0, new Details(base.title(), base.description(), categoryA, base.location(), new BigDecimal("300"), new BigDecimal("200"), base.requirements())));
        assertTrue(service.list(company, "", null).isEmpty());
        var requirements = List.of(base.requirements().getFirst(), new Requirement(Kind.DEGREE, "BACHELOR", 0, Priority.REQUIRED, true),
                new Requirement(Kind.FIELD, "Computer Science", 0, Priority.REQUIRED, true), new Requirement(Kind.EXPERIENCE, "", 12, Priority.REQUIRED, true),
                new Requirement(Kind.EXPERIENCE_TAG, skillId, 0, Priority.REQUIRED, false), new Requirement(Kind.PROJECT, skillId, 0, Priority.OPTIONAL, false),
                new Requirement(Kind.CERTIFICATION, "Demo Certificate", 0, Priority.OPTIONAL, false));
        long job = service.submit(company, 0, new Details(base.title(), base.description(), categoryA, base.location(), base.salaryMin(), base.salaryMax(), requirements));
        assertEquals(7, service.open(company, job).details().requirements().size());
        String conflict = "test-" + UUID.randomUUID();
        assertThrows(UserError.class, () -> service.saveSkill(admin, new Skill(conflict, "Other", Skill.Type.SOFT, Set.of(skillId))));
        assertTrue(service.skills(admin).stream().noneMatch(s -> s.id().equals(conflict)));
        service.saveSkill(admin, new Skill(skillId, "Renamed " + skillId, Skill.Type.SOFT, Set.of("new-" + skillId)));
        assertEquals(skillId, service.open(company, job).details().requirements().getFirst().value());
        service.saveCategory(admin, categoryA, "Renamed " + UUID.randomUUID());
        assertEquals(categoryA, service.open(company, job).details().categoryId());
        assertThrows(UserError.class, () -> service.saveCategory(student, 0, "Forbidden"));
    }

    @Test void repositoryRollsBackAJobAndItsRequirementsTogether() {
        service.verify(admin, companyId, true);
        var repository = new JdbcJobs(database);
        assertThrows(IllegalStateException.class, () -> repository.transaction(store -> {
            store.saveJob(companyId, 0, details(categoryA));
            throw new IllegalStateException("Simulated interruption before commit");
        }));
        assertTrue(service.list(company, "", null).isEmpty());
    }

    @AfterEach void cleanup() throws Exception {
        try (var connection = database.connect()) {
            for (long id : accounts) {
                execute(connection, "DELETE r FROM job_requirements r JOIN jobs j ON j.id=r.job_id WHERE j.company_id=?", id);
                execute(connection, "DELETE FROM jobs WHERE company_id=?", id);
                execute(connection, "DELETE FROM students WHERE user_id=?", id);
                execute(connection, "DELETE FROM companies WHERE user_id=?", id);
                execute(connection, "DELETE FROM users WHERE id=?", id);
            }
            for (long id : categories) { execute(connection, "DELETE FROM categories WHERE id=?", id); }
            for (String id : skillIds) { execute(connection, "DELETE FROM skill_aliases WHERE skill_id=?", id); execute(connection, "DELETE FROM skills WHERE id=?", id); }
        }
    }
    private static void execute(java.sql.Connection connection, String sql, Object id) throws Exception {
        try (var statement = connection.prepareStatement(sql)) { statement.setObject(1, id); statement.executeUpdate(); }
    }
}
