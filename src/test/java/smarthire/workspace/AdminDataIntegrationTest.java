package smarthire.workspace;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import smarthire.WorkflowFixture;
import smarthire.applications.Application;
import smarthire.identity.UserError;
import smarthire.jobs.Job;
import smarthire.storage.JdbcWorkspace;
import static org.junit.jupiter.api.Assertions.*;
import static smarthire.workspace.AdminData.*;

@EnabledIfSystemProperty(named = "smarthire.integration", matches = "true")
class AdminDataIntegrationTest {
    @TempDir Path root;
    WorkflowFixture fixture;
    WorkspaceService workspace;

    @BeforeEach void open() {
        fixture = new WorkflowFixture(root);
        workspace = new WorkspaceService(fixture.identity, new JdbcWorkspace(fixture.database), fixture.documents);
    }

    @AfterEach void close() throws Exception { if (fixture != null) { fixture.close(); } }

    @Test void studentExportIncludesOriginalsAndEvidenceWithoutCredentialsOrDatabaseChanges() throws Exception {
        var cv = fixture.confirm(fixture.student, List.of(fixture.skill));
        long job = fixture.publish();
        var application = fixture.applications.apply(fixture.student, job);
        long student = fixture.identity.profile(fixture.student).id();
        var target = new Target(Kind.STUDENT, Long.toString(student));
        var file = root.resolve("student.zip");
        assertThrows(UserError.class, () -> workspace.exportData(fixture.company, target, file));
        assertFalse(Files.exists(file));
        assertEquals(0, workspace.exportData(fixture.admin, target, file).unavailableOriginals());
        try (var zip = new ZipFile(file.toFile())) {
            assertFalse(text(zip, "account.txt").contains("password_hash"));
            assertTrue(text(zip, "applications.txt").contains(application.id().toString()));
            assertTrue(text(zip, "application_notices.txt").contains(application.id().toString()));
            assertTrue(text(zip, "cv_approvals.txt").contains("APPROVED"));
            assertArrayEquals(Files.readAllBytes(fixture.pdf), zip.getInputStream(zip.getEntry("originals/" + cv.documentId() + ".pdf")).readAllBytes());
        }
        assertThrows(UserError.class, () -> workspace.exportData(fixture.admin, target, file));
        assertEquals(application.id(), fixture.applications.open(fixture.student, application.id()).application().id());
    }

    @Test void erasingOneVersionKeepsSharedBytesAndClosesAJobThatLosesItsHire() throws Exception {
        long job = fixture.publish();
        var original = fixture.confirm(fixture.student, List.of(fixture.skill));
        var application = fixture.applications.apply(fixture.student, job);
        var target = new Target(Kind.CV, original.id().toString());
        var beforeHire = workspace.eraseImpact(fixture.admin, target);
        fixture.applications.transition(fixture.company, application.id(), Application.Status.SHORTLISTED);
        fixture.applications.transition(fixture.company, application.id(), Application.Status.HIRED);
        var replacement = fixture.confirmPending(fixture.student, List.of(fixture.skill));
        assertEquals(original.documentId(), replacement.documentId());
        var impact = workspace.eraseImpact(fixture.admin, target);
        assertEquals(1, impact.applications());
        assertEquals(1, impact.closures());
        assertThrows(UserError.class, () -> workspace.eraseData(fixture.student, beforeHire));
        assertEquals(0, workspace.eraseData(fixture.admin, impact).filesPendingCleanup());
        assertEquals(Job.Status.CLOSED, fixture.jobs.open(fixture.company, job).status());
        assertThrows(UserError.class, () -> fixture.applications.open(fixture.student, application.id()));
        assertEquals(replacement.id(), fixture.cvs.current(fixture.student).orElseThrow().id());
        assertTrue(Files.exists(root.resolve(original.documentId() + ".pdf")));
        var current = workspace.eraseImpact(fixture.admin, new Target(Kind.CV, replacement.id().toString()));
        workspace.eraseData(fixture.admin, current);
        assertTrue(fixture.cvs.current(fixture.student).isEmpty());
        assertFalse(Files.exists(root.resolve(original.documentId() + ".pdf")));
    }

    @Test void erasingJobsAndCompaniesRetainsStudentsCvAndErasingStudentsInvalidatesTheirSessions() {
        long job = fixture.jobs.submit(fixture.company, 0, fixture.details());
        fixture.jobs.attachCircular(fixture.company, job, fixture.pdf);
        fixture.jobs.moderate(fixture.admin, job, true, "");
        var cv = fixture.confirm(fixture.student, List.of(fixture.skill));
        var jobTarget = new Target(Kind.JOB, Long.toString(job));
        var stale = workspace.eraseImpact(fixture.admin, jobTarget);
        var application = fixture.applications.apply(fixture.student, job);
        assertThrows(UserError.class, () -> workspace.eraseData(fixture.admin, stale));
        var jobImpact = workspace.eraseImpact(fixture.admin, jobTarget);
        assertEquals(1, jobImpact.originals());
        assertEquals(0, workspace.eraseData(fixture.admin, jobImpact).filesPendingCleanup());
        assertThrows(UserError.class, () -> fixture.jobs.open(fixture.company, job));
        assertFalse(workspace.notices(fixture.student).stream().anyMatch(n -> n.message().contains(application.id().toString())));

        long second = fixture.publish();
        fixture.applications.apply(fixture.student, second);
        var company = new Target(Kind.COMPANY, Long.toString(fixture.companyId));
        workspace.eraseData(fixture.admin, workspace.eraseImpact(fixture.admin, company));
        assertThrows(UserError.class, () -> fixture.identity.profile(fixture.company));
        assertTrue(fixture.applications.list(fixture.student, null, null).isEmpty());
        assertEquals(cv.id(), fixture.cvs.current(fixture.student).orElseThrow().id());

        var student = new Target(Kind.STUDENT, Long.toString(fixture.identity.profile(fixture.student).id()));
        workspace.eraseData(fixture.admin, workspace.eraseImpact(fixture.admin, student));
        assertThrows(UserError.class, () -> fixture.identity.profile(fixture.student));
        assertFalse(Files.exists(root.resolve(cv.documentId() + ".pdf")));
        var admin = new Target(Kind.STUDENT, Long.toString(fixture.identity.profile(fixture.admin).id()));
        assertThrows(UserError.class, () -> workspace.eraseImpact(fixture.admin, admin));
    }

    @Test void failedEraseRollsBackRowsAndLeavesOriginalBytes() {
        var cv = fixture.confirm(fixture.student, List.of(fixture.skill));
        var impact = workspace.eraseImpact(fixture.admin, new Target(Kind.CV, cv.id().toString()));
        var delegate = new JdbcWorkspace(fixture.database);
        var failing = new WorkspaceRepository() {
            @Override public <T> T transaction(Function<Store, T> work) {
                return delegate.transaction(store -> { work.apply(store); throw new UserError("Injected rollback"); });
            }
        };
        var service = new WorkspaceService(fixture.identity, failing, fixture.documents);
        assertThrows(UserError.class, () -> service.eraseData(fixture.admin, impact));
        assertEquals(cv.id(), fixture.cvs.current(fixture.student).orElseThrow().id());
        assertTrue(Files.exists(root.resolve(cv.documentId() + ".pdf")));
    }

    private static String text(ZipFile zip, String name) throws Exception {
        try (var input = zip.getInputStream(zip.getEntry(name))) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
