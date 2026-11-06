package smarthire.workspace;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import smarthire.WorkflowFixture;
import smarthire.applications.Application;
import smarthire.identity.*;
import smarthire.storage.*;
import static org.junit.jupiter.api.Assertions.*;
import static smarthire.workspace.WorkspaceRepository.*;

@EnabledIfSystemProperty(named = "smarthire.integration", matches = "true")
class WorkspaceIntegrationTest {
    @TempDir Path root;
    WorkflowFixture f;
    WorkspaceService workspace;
    @BeforeEach void setup() { f = new WorkflowFixture(root); workspace = new WorkspaceService(f.identity, new JdbcWorkspace(f.database)); }
    @AfterEach void cleanup() throws Exception { if (f != null) { f.close(); } }

    @Test void committedEventsPersistAndInboxReadStateIsOwnedAndIdempotent() {
        assertTrue(workspace.notices(f.company).stream().anyMatch(n -> n.message().contains("verified")));
        int initial = workspace.notices(f.company).size(); f.jobs.verify(f.admin, f.companyId, true);
        assertEquals(initial, workspace.notices(f.company).size());
        long job = f.publish();
        assertTrue(workspace.notices(f.company).stream().anyMatch(n -> n.message().contains("Job #" + job + " approved")));
        long rejected = f.jobs.submit(f.company, 0, f.details()); f.jobs.moderate(f.admin, rejected, false, "Add context");
        assertTrue(workspace.notices(f.company).stream().anyMatch(n -> n.message().contains("Add context")));
        long longReason = f.jobs.submit(f.company, 0, f.details()); f.jobs.moderate(f.admin, longReason, false, "x".repeat(1000));
        assertEquals(1000, f.jobs.open(f.company, longReason).decision().length());
        assertTrue(workspace.notices(f.company).stream().allMatch(n -> n.message().length() <= 1000));
        f.confirm(f.student, List.of(f.skill)); int initialNotices = workspace.notices(f.student).size(); var a = f.applications.apply(f.student, job);
        f.applications.transition(f.company, a.id(), Application.Status.SHORTLISTED);
        f.applications.transition(f.student, a.id(), Application.Status.WITHDRAWN);
        var inbox = workspace.notices(f.student); assertEquals(initialNotices + 3, inbox.size());
        assertTrue(inbox.stream().allMatch(n -> n.readAt() == null));
        assertTrue(workspace.notices(f.secondStudent).isEmpty()); assertTrue(workspace.notices(f.foreignCompany).isEmpty());
        UUID id = inbox.getFirst().id();
        assertThrows(UserError.class, () -> workspace.markRead(f.secondStudent, id));
        workspace.markRead(f.student, id);
        var readAt = workspace.notices(f.student).stream().filter(n -> n.id().equals(id)).findFirst().orElseThrow().readAt();
        workspace.markRead(f.student, id);
        var restarted = new WorkspaceService(f.identity, new JdbcWorkspace(f.database));
        assertEquals(readAt, restarted.notices(f.student).stream().filter(n -> n.id().equals(id)).findFirst().orElseThrow().readAt());
        assertEquals(initialNotices + 2, restarted.dashboard(f.student).counts().get("Unread notifications"));
    }

    @Test void rollbackAndRefusedActionsProduceNoNotifications() {
        int before = workspace.notices(f.company).size();
        assertThrows(IllegalStateException.class, () -> new JdbcJobs(f.database).transaction(store -> {
            store.verify(f.companyId, false); throw new IllegalStateException("Injected failure after event write");
        }));
        assertEquals(before, workspace.notices(f.company).size());
        assertTrue(f.jobs.companies(f.admin).stream().filter(c -> c.id() == f.companyId).findFirst().orElseThrow().verified());
        long job = f.publish(); f.confirm(f.student, List.of(f.skill)); var a = f.applications.apply(f.student, job);
        int studentBefore = workspace.notices(f.student).size(), companyBefore = workspace.notices(f.company).size();
        assertThrows(IllegalStateException.class, () -> new JdbcApplications(f.database).transaction(store -> {
            store.lock(a.id()).orElseThrow();
            store.status(a, Application.Status.SHORTLISTED, smarthire.identity.Role.COMPANY, java.time.Instant.now());
            throw new IllegalStateException("Injected failure after event write");
        }));
        assertEquals(Application.Status.APPLIED, f.applications.open(f.student, a.id()).application().status());
        assertEquals(1, f.applications.timeline(f.student, a.id()).events().size());
        assertThrows(UserError.class, () -> f.applications.apply(f.student, job));
        assertThrows(UserError.class, () -> f.applications.transition(f.company, a.id(), Application.Status.HIRED));
        assertEquals(studentBefore, workspace.notices(f.student).size()); assertEquals(companyBefore, workspace.notices(f.company).size());
        // A submission and both participant alerts also roll back together.
        long other = f.publish(); var template = new Application(UUID.randomUUID(), a.studentId(), other, a.cvVersion(), a.submittedAt(), a.status(), a.submittedProfile(), a.originalPolicy(), a.originalScore(), a.originalExplanation());
        assertThrows(IllegalStateException.class, () -> new JdbcApplications(f.database).transaction(store -> { store.insert(template); throw new IllegalStateException("Fail after insert"); }));
        assertTrue(f.applications.list(f.student, other, null).isEmpty());
        assertEquals(studentBefore, workspace.notices(f.student).size());
    }

    @Test void monitoringCombinesAllFiltersAndCannotChangeRecruiterDecisions() {
        long first = f.publish(), second = f.publish();
        f.confirm(f.student, List.of(f.skill)); f.confirm(f.secondStudent, List.of(f.skill));
        var a = f.applications.apply(f.student, first); f.applications.apply(f.secondStudent, second);
        f.applications.transition(f.company, a.id(), Application.Status.SHORTLISTED);
        var filter = new Filter(first, f.companyId, a.studentId(), Application.Status.SHORTLISTED);
        var records = workspace.monitor(f.admin, filter); assertEquals(1, records.size()); assertEquals(a.id(), records.getFirst().id());
        assertEquals(a.originalExplanation(), records.getFirst().originalExplanation());
        for (Filter mismatch : List.of(new Filter(second, f.companyId, a.studentId(), null), new Filter(first, f.identity.profile(f.foreignCompany).id(), null, null), new Filter(first, null, null, Application.Status.HIRED))) {
            assertTrue(workspace.monitor(f.admin, mismatch).isEmpty());
        }
        for (UUID actor : List.of(f.student, f.company)) {
            assertThrows(UserError.class, () -> workspace.monitor(actor, filter));
            assertThrows(UserError.class, () -> workspace.accounts(actor));
            assertThrows(UserError.class, () -> workspace.setActive(actor, a.studentId(), false));
        }
        assertThrows(UserError.class, () -> f.applications.transition(f.admin, a.id(), Application.Status.HIRED));
        assertEquals(Application.Status.SHORTLISTED, f.applications.open(f.company, a.id()).application().status());
    }

    @Test void disablementBlocksExistingSessionsAndLoginWhileKeepingHistoryAndBytes() {
        long job = f.publish(); f.confirm(f.student, List.of(f.skill)); var a = f.applications.apply(f.student, job);
        byte[] original = f.applications.download(f.company, a.id()); long studentId = a.studentId();
        String email = f.identity.profile(f.student).email();
        workspace.setActive(f.admin, studentId, false);
        assertThrows(UserError.class, () -> workspace.dashboard(f.student));
        assertThrows(UserError.class, () -> f.identity.login(email, "Workflow-test!21".toCharArray()));
        assertThrows(UserError.class, () -> f.applications.transition(f.student, a.id(), Application.Status.WITHDRAWN));
        assertArrayEquals(original, f.applications.download(f.company, a.id()));
        assertEquals(a.originalExplanation(), workspace.monitor(f.admin, new Filter(job, null, studentId, null)).getFirst().originalExplanation());
        workspace.setActive(f.admin, studentId, true); UUID signedIn = f.identity.login(email, "Workflow-test!21".toCharArray());
        workspace.setActive(f.admin, f.companyId, false);
        assertThrows(UserError.class, () -> f.jobs.submit(f.company, 0, f.details()));
        assertThrows(UserError.class, () -> f.applications.download(f.company, a.id()));
        assertArrayEquals(original, f.applications.download(signedIn, a.id()));
        assertThrows(UserError.class, () -> workspace.setActive(f.admin, f.identity.profile(f.admin).id(), false));
        assertFalse(workspace.accounts(f.admin).stream().filter(u -> u.id() == f.companyId).findFirst().orElseThrow().active());
    }

    @Test void dashboardsUseOnlyTheActorsApplicationsAndRefreshCommittedCounts() {
        long job = f.publish(); f.confirm(f.student, List.of(f.skill)); f.confirm(f.secondStudent, List.of(f.skill));
        var a = f.applications.apply(f.student, job); f.applications.apply(f.secondStudent, job);
        f.applications.transition(f.company, a.id(), Application.Status.SHORTLISTED);
        assertEquals(1, workspace.dashboard(f.student).counts().get("Applications"));
        assertEquals(1, workspace.dashboard(f.student).applicationStatuses().get("SHORTLISTED"));
        assertEquals(0, workspace.dashboard(f.student).applicationStatuses().get("APPLIED"));
        assertEquals(1, workspace.dashboard(f.secondStudent).applicationStatuses().get("APPLIED"));
        assertEquals(2, workspace.dashboard(f.company).counts().get("Applicants"));
        assertEquals(0, workspace.dashboard(f.foreignCompany).counts().get("Applicants"));
        assertTrue(workspace.dashboard(f.admin).counts().get("Applications") >= 2);
        assertEquals(1, workspace.dashboard(f.company).counts().get("Approved jobs"));
        f.jobs.close(f.company, job); assertEquals(0, workspace.dashboard(f.company).counts().get("Approved jobs"));
        assertEquals(2, workspace.dashboard(f.company).counts().get("Applicants"));
    }
}
