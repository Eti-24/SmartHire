package smarthire.applications;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import smarthire.WorkflowFixture;
import smarthire.identity.UserError;
import smarthire.storage.JdbcApplications;
import smarthire.storage.JdbcWorkspace;
import smarthire.workspace.AdminData;
import smarthire.workspace.WorkspaceService;
import static org.junit.jupiter.api.Assertions.*;
import static smarthire.applications.Interview.Action.*;

@EnabledIfSystemProperty(named = "smarthire.integration", matches = "true")
class InterviewIntegrationTest {
    @TempDir Path root;
    private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    @Test void schedulingRespectsOwnershipRevisionsAndClosureAndErasesWithTheSubmittedCv() throws Exception {
        try (var f = new WorkflowFixture(root)) {
            long job = f.publish();
            var cv = f.confirm(f.student, List.of(f.skill));
            UUID id = f.applications.apply(f.student, job).id();
            var service = service(f, now);
            var slot = slot(60);
            assertThrows(UserError.class, () -> service.changeInterview(f.company, id, 0, PROPOSE, slot));
            service.transition(f.company, id, Application.Status.SHORTLISTED);
            f.jobs.close(f.company, job);
            assertThrows(UserError.class, () -> service.changeInterview(f.foreignCompany, id, 0, PROPOSE, slot));
            service.changeInterview(f.company, id, 0, PROPOSE, slot);
            assertThrows(UserError.class, () -> service.interview(f.secondStudent, id));
            service.changeInterview(f.student, id, 1, ACCEPT, null);
            service.changeInterview(f.student, id, 2, RESCHEDULE, null);
            var replacement = service.changeInterview(f.company, id, 3, PROPOSE, slot(120));
            assertThrows(UserError.class, () -> service.changeInterview(f.student, id, 1, ACCEPT, null));
            assertThrows(UserError.class, () -> service(f, replacement.slot().start()).changeInterview(f.student, id, 4, ACCEPT, null));
            service.changeInterview(f.student, id, 4, ACCEPT, null);
            assertEquals(Application.Status.SHORTLISTED, service.open(f.student, id).application().status());
            var admin = new WorkspaceService(f.identity, new JdbcWorkspace(f.database), f.documents);
            admin.setActive(f.admin, f.identity.profile(f.student).id(), false);
            assertEquals(Interview.Status.CANCELLED, service.interview(f.company, id).current().status());
            assertThrows(UserError.class, () -> service.interviews(f.student));
            var target = new AdminData.Target(AdminData.Kind.CV, cv.id().toString());
            var impact = admin.eraseImpact(f.admin, target);
            assertEquals(6, impact.interviewEvents());
            assertEquals(6, impact.interviewNotices());
            admin.eraseData(f.admin, impact);
            assertTrue(service.interviews(f.company).isEmpty());
            assertTrue(admin.notices(f.company).stream().noneMatch(n -> n.message().contains("Open Interviews")));
        }
    }

    @Test void overlappingAcceptanceSerializesAndFinalHireCancelsOtherOpenInterviews() throws Exception {
        try (var f = new WorkflowFixture(root)) {
            long job = f.publish();
            f.confirm(f.student, List.of(f.skill));
            f.confirm(f.secondStudent, List.of(f.skill));
            UUID first = shortlist(f, f.student, job), second = shortlist(f, f.secondStudent, job);
            var service = service(f, now);
            service.changeInterview(f.company, first, 0, PROPOSE, slot(60));
            service.changeInterview(f.company, second, 0, PROPOSE, slot(60));
            var start = new CountDownLatch(1);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var a = executor.submit(() -> { start.await(); return accept(service, f.student, first); });
                var b = executor.submit(() -> { start.await(); return accept(service, f.secondStudent, second); });
                start.countDown();
                assertNotEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            }
            UUID confirmed = service.interview(f.company, first).current().status() == Interview.Status.CONFIRMED ? first : second;
            UUID other = confirmed.equals(first) ? second : first;
            UUID otherStudent = other.equals(first) ? f.student : f.secondStudent;
            service.changeInterview(f.company, other, 1, PROPOSE, slot(90));
            service.changeInterview(otherStudent, other, 2, ACCEPT, null);
            service.transition(f.company, confirmed, Application.Status.HIRED);
            assertEquals(Interview.Status.CANCELLED, service.interview(f.company, confirmed).current().status());
            assertEquals("Posting filled", service.interview(f.company, other).current().reason());
            assertEquals(Application.Status.SHORTLISTED, service.open(f.company, other).application().status());
        }
    }

    private static UUID shortlist(WorkflowFixture f, UUID student, long job) {
        UUID id = f.applications.apply(student, job).id();
        f.applications.transition(f.company, id, Application.Status.SHORTLISTED);
        return id;
    }

    private static boolean accept(ApplicationService service, UUID session, UUID id) {
        try { service.changeInterview(session, id, 1, ACCEPT, null); return true; }
        catch (UserError conflict) { assertTrue(conflict.getMessage().contains("overlaps")); return false; }
    }

    private Interview.Slot slot(long minutes) {
        return new Interview.Slot(now.plusSeconds(minutes * 60), now.plusSeconds((minutes + 30) * 60), ZoneId.of("Asia/Dhaka"), "Main office");
    }

    private static ApplicationService service(WorkflowFixture f, Instant now) {
        return new ApplicationService(f.identity, new JdbcApplications(f.database), f.documents, Clock.fixed(now, ZoneOffset.UTC));
    }
}
