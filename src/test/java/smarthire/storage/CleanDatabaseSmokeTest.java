package smarthire.storage;

import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import smarthire.*;
import smarthire.applications.*;
import smarthire.cv.*;
import smarthire.demo.DemoScenario;
import smarthire.documents.*;
import smarthire.identity.*;
import smarthire.jobs.*;
import smarthire.matching.*;
import smarthire.workspace.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in: creates and drops only its own unpredictable temporary schema. */
@EnabledIfSystemProperty(named = "smarthire.cleanSmoke", matches = "true")
class CleanDatabaseSmokeTest {
    @TempDir Path root;
    @Test void emptySchemaToCompleteWorkflowAndRestartWithoutTouchingExistingDatabases() throws Exception {
        Path adminFile = Path.of(System.getProperty("smarthire.smokeAdminConfig", ".local/mysql-admin.ini"));
        var admin = new Properties(); try (var input = Files.newInputStream(adminFile)) { admin.load(input); }
        var credentials = new Properties(); credentials.setProperty("user", admin.getProperty("user")); credentials.setProperty("password", admin.getProperty("password"));
        String schema = "smarthire_smoke_" + UUID.randomUUID().toString().replace("-", "");
        boolean created = false;
        try (var control = DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectTimeout=3000&socketTimeout=10000", credentials)) {
            try {
                try (var statement = control.createStatement()) { statement.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4"); created = true; }
                var database = Database.loadSchema(adminFile, schema); database.initialize(); database.initialize();
                try (var connection = database.connect(); var query = connection.createStatement(); var row = query.executeQuery("SELECT VERSION()")) {
                    row.next(); System.out.println("Clean smoke MySQL server: " + row.getString(1));
                }
                var accounts = new JdbcAccounts(database); DemoSeed.provision(accounts); DemoJobs.provision(database);
                var identity = new IdentityService(accounts);
                UUID administrator = identity.login("admin@smarthire.example", "SmartHireDemo!21".toCharArray());
                UUID company = identity.login("company@smarthire.example", "SmartHireDemo!21".toCharArray());
                UUID student = identity.login("student@smarthire.example", "SmartHireDemo!21".toCharArray());
                try (var documents = new ManagedDocuments(root.resolve("documents"), new JdbcDocumentReferences(database))) {
                    assertTrue(documents.reconcile().unavailableReferences().isEmpty());
                    var jobs = new JobService(identity, new JdbcJobs(database), documents);
                    var newStudent = identity.login("student.nusrat@smarthire.example", "SmartHireDemo!21".toCharArray());
                    var visibleSeedJobs = jobs.list(newStudent, "", null);
                    assertEquals(5, visibleSeedJobs.size());
                    assertEquals(2, visibleSeedJobs.stream().filter(post -> post.details().title().equals("Junior Java Developer"))
                            .findFirst().orElseThrow().remainingVacancies());
                    var businessCompany = identity.login("company.padmabridge@smarthire.example", "SmartHireDemo!21".toCharArray());
                    var pendingSeed = jobs.list(businessCompany, "", null).stream()
                            .filter(post -> post.details().title().equals("Junior Reporting Analyst")).findFirst().orElseThrow();
                    assertEquals(Job.Status.PENDING, pendingSeed.status());
                    assertThrows(UserError.class, () -> jobs.open(newStudent, pendingSeed.id()));
                    jobs.moderate(administrator, pendingSeed.id(), false, "Keep this decision during reseeding.");
                    var cvs = new CvService(identity, new JdbcCvs(database), documents);
                    var applications = new ApplicationService(identity, new JdbcApplications(database), documents);
                    var workspace = new WorkspaceService(identity, new JdbcWorkspace(database));
                    var approvals = new CvApprovalService(identity, new JdbcCvApprovals(database), documents);
                    long category = jobs.categories(company).stream().filter(c -> c.name().equals("Software")).findFirst().orElseThrow().id();
                    long job = jobs.submit(company, 0, DemoScenario.job(category)); jobs.moderate(administrator, job, true, "");
                    var pending = confirm(cvs, student, "demo-cv-mandatory-gap.pdf");
                    assertThrows(UserError.class, () -> applications.apply(student, job));
                    approvals.decide(administrator, pending.id(), CvApproval.Status.APPROVED, "");
                    var blocked = new MatchService(identity, cvs, jobs).open(student, job).match();
                    assertEquals(100.0 * 19 / 21, blocked.percentage(), 1e-9); assertFalse(blocked.eligible());
                    assertThrows(UserError.class, () -> applications.apply(student, job));
                    var eligible = confirm(cvs, student, "demo-cv-eligible.pdf");
                    assertThrows(UserError.class, () -> applications.apply(student, job));
                    approvals.decide(administrator, eligible.id(), CvApproval.Status.APPROVED, "");
                    var application = applications.apply(student, job); byte[] original = applications.download(company, application.id());
                    confirm(cvs, student, "demo-cv-skill-gap.pdf");
                    assertArrayEquals(original, applications.download(company, application.id()));
                    applications.transition(company, application.id(), Application.Status.SHORTLISTED);
                    applications.transition(company, application.id(), Application.Status.HIRED);
                    assertThrows(UserError.class, () -> jobs.submit(company, job, DemoScenario.job(category)));
                    var notice = workspace.notices(student).getFirst(); workspace.markRead(student, notice.id());
                    // Repeated seeding preserves confirmed reviews, applications, decisions and notifications.
                    DemoSeed.provision(accounts); DemoJobs.provision(database); database.initialize();
                    assertEquals(5, jobs.list(company, "", null).size());
                    assertEquals(2, jobs.list(businessCompany, "", null).size());
                    assertEquals(Job.Status.REJECTED, jobs.open(businessCompany, pendingSeed.id()).status());
                    assertEquals(5, jobs.list(newStudent, "", null).size());
                    assertEquals(Application.Status.HIRED, new ApplicationService(identity, new JdbcApplications(database), documents).open(student, application.id()).application().status());
                    assertArrayEquals(original, applications.download(student, application.id()));
                    assertNotNull(new WorkspaceService(identity, new JdbcWorkspace(database)).notices(student).stream().filter(n -> n.id().equals(notice.id())).findFirst().orElseThrow().readAt());
                    assertEquals(1, workspace.monitor(administrator, new WorkspaceRepository.Filter(job, null, null, null)).size());
                    assertEquals(1, workspace.dashboard(student).applicationStatuses().get("HIRED"));
                    verifyVacancyUpgrade(database, jobs, company, student, job, application.id());
                }
                // Reopen the actual managed store with a fresh reference repository.
                try (var reopened = new ManagedDocuments(root.resolve("documents"), new JdbcDocumentReferences(database))) { assertTrue(reopened.reconcile().unavailableReferences().isEmpty()); }
                System.out.println("Clean-database smoke passed: fresh schema, seeding, parsing, eligibility, application snapshot, decisions, notifications and restart.");
            } finally {
                if (created) { try (var statement = control.createStatement()) { statement.execute("DROP DATABASE " + schema); } }
            }
        }
    }

    private static void verifyVacancyUpgrade(Database database, JobService jobs, UUID company, UUID student, long job, UUID application) throws Exception {
        try (var connection = database.connect(); var statement = connection.createStatement()) {
            statement.execute("DELETE FROM schema_migrations WHERE version=1");
            statement.execute("ALTER TABLE jobs DROP CHECK job_positive_vacancy_count, DROP COLUMN vacancy_count");
            statement.execute("ALTER TABLE applications DROP COLUMN hired_at");
        }
        database.initialize();
        try (var connection = database.connect(); var statement = connection.createStatement()) {
            // Simulate interruption after the first DDL step, before recording the migration.
            statement.execute("DELETE FROM schema_migrations WHERE version=1");
            statement.execute("ALTER TABLE applications DROP COLUMN hired_at");
        }
        database.initialize();
        database.initialize();
        assertEquals(1, jobs.open(company, job).details().vacancyCount());
        assertEquals(0, jobs.open(company, job).remainingVacancies());
        assertThrows(UserError.class, () -> jobs.open(student, job));
        var legacy = jobs.companyHires(company).stream().filter(row -> row.applicationId().equals(application)).findFirst().orElseThrow();
        assertNull(legacy.hiredAt());
    }
    private static CvProfile confirm(CvService cvs, UUID session, String name) {
        try (var draft = cvs.upload(session, Path.of("src/test/resources/demo", name))) { return cvs.confirm(session, draft, draft.suggestions()); }
    }
}
