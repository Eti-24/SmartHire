package smarthire.applications;

import java.nio.file.Path;
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
import smarthire.jobs.Job;
import smarthire.jobs.JobService;
import smarthire.matching.MatchService;
import smarthire.storage.JdbcWorkspace;
import smarthire.workspace.AdminData;
import smarthire.workspace.WorkspaceService;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "smarthire.integration", matches = "true")
class VacancyIntegrationTest {
    @TempDir Path root;

    @Test void onlyFinalHireRemovesPostingFromDiscoveryAndOnlyOneConcurrentHireWins() throws Exception {
        try (var f = new WorkflowFixture(root)) {
            assertThrows(UserError.class, () -> f.jobs.submit(f.company, 0, capacity(f, 0)));
            long job = f.publish(capacity(f, 2));
            var third = f.newStudent();
            for (var student : List.of(f.student, f.secondStudent, third)) { f.confirm(student, List.of(f.skill)); }
            f.jobs.saveBookmark(third, job);
            UUID first = shortlist(f, f.student, job);
            UUID second = shortlist(f, f.secondStudent, job);
            UUID last = shortlist(f, third, job);
            assertThrows(UserError.class, () -> f.jobs.submit(f.company, job, capacity(f, 3)));
            f.applications.transition(f.company, first, Application.Status.HIRED);
            assertEquals(1, f.jobs.open(third, job).remainingVacancies());
            assertEquals(JobService.SavedStatus.AVAILABLE, f.jobs.savedJobs(third).getFirst().status());
            assertTrue(f.jobs.hiringHistory(third).stream().noneMatch(row -> row.jobId() == job));
            assertEquals(first, f.jobs.companyHires(f.company).getFirst().applicationId());
            assertNotNull(f.jobs.companyHires(f.company).getFirst().hiredAt());
            assertTrue(f.jobs.companyHires(f.foreignCompany).isEmpty());
            assertThrows(UserError.class, () -> f.jobs.companyHires(third));
            var workspace = new WorkspaceService(f.identity, new JdbcWorkspace(f.database));
            long before = workspace.dashboard(third).counts().get("Available jobs");
            var start = new CountDownLatch(1);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var a = executor.submit(() -> { start.await(); return hire(f, second); });
                var b = executor.submit(() -> { start.await(); return hire(f, last); });
                start.countDown();
                assertNotEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            }
            assertEquals(2, f.jobs.companyHires(f.company).size());
            assertTrue(f.jobs.hiringHistory(third).stream().anyMatch(row -> row.jobId() == job && row.vacancyCount() == 2));
            assertEquals(before - 1, workspace.dashboard(third).counts().get("Available jobs"));
            assertEquals(JobService.SavedStatus.FILLED, f.jobs.savedJobs(third).getFirst().status());
            assertThrows(UserError.class, () -> new MatchService(f.identity, f.cvs, f.jobs).open(third, job));
            assertThrows(UserError.class, () -> f.jobs.circular(third, job));
        }
    }

    @Test void erasureClosesPreviouslyFilledJobsAndReportsNewOpenCapacityForPartialJobs() throws Exception {
        try (var f = new WorkflowFixture(root)) {
            long partial = f.publish(capacity(f, 2)), full = f.publish(capacity(f, 2));
            var first = f.confirm(f.student, List.of(f.skill));
            f.confirm(f.secondStudent, List.of(f.skill));
            UUID partialHire = shortlist(f, f.student, partial);
            shortlist(f, f.secondStudent, partial);
            UUID firstFull = shortlist(f, f.student, full), secondFull = shortlist(f, f.secondStudent, full);
            for (UUID hire : List.of(partialHire, firstFull, secondFull)) { f.applications.transition(f.company, hire, Application.Status.HIRED); }
            var admin = new WorkspaceService(f.identity, new JdbcWorkspace(f.database), f.documents);
            var target = new AdminData.Target(AdminData.Kind.CV, first.id().toString());
            var impact = admin.eraseImpact(f.admin, target);
            assertEquals(1, impact.closures());
            assertEquals(1, impact.additionalOpenVacancies());
            admin.eraseData(f.admin, impact);
            assertEquals(2, f.jobs.open(f.secondStudent, partial).remainingVacancies());
            assertEquals(Job.Status.CLOSED, f.jobs.open(f.company, full).status());
            assertThrows(UserError.class, () -> f.jobs.open(f.secondStudent, full));
            assertEquals(List.of(secondFull), f.jobs.companyHires(f.company).stream().map(Job.CompanyHire::applicationId).toList());
        }
    }

    private static UUID shortlist(WorkflowFixture f, UUID student, long job) {
        UUID id = f.applications.apply(student, job).id();
        f.applications.transition(f.company, id, Application.Status.SHORTLISTED);
        return id;
    }

    private static boolean hire(WorkflowFixture f, UUID application) {
        try { f.applications.transition(f.company, application, Application.Status.HIRED); return true; }
        catch (UserError e) { assertTrue(e.getMessage().contains("filled")); return false; }
    }

    private static Job.Details capacity(WorkflowFixture f, int vacancies) {
        var d = f.details();
        return new Job.Details(d.title(), d.description(), d.categoryId(), d.location(), d.salaryMin(), d.salaryMax(), d.requirements(), vacancies);
    }
}
