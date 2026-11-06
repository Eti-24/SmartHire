package smarthire.jobs;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import smarthire.WorkflowFixture;
import smarthire.applications.Application.Status;
import smarthire.identity.UserError;
import smarthire.storage.JdbcJobs;
import smarthire.storage.JdbcWorkspace;
import smarthire.workspace.AdminData.Kind;
import smarthire.workspace.AdminData.Target;
import smarthire.workspace.WorkspaceService;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "smarthire.integration", matches = "true")
class SavedJobsIntegrationTest {
    @TempDir Path root;

    @Test void duplicateBookmarksArePrivateAndRefreshWithPublicationAndHiring() throws Exception {
        try (var f = new WorkflowFixture(root)) {
            long job = f.publish();
            var restarted = new JobService(f.identity, new JdbcJobs(f.database), f.documents);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var first = executor.submit(() -> f.jobs.saveBookmark(f.student, job));
                var second = executor.submit(() -> restarted.saveBookmark(f.student, job));
                first.get(10, TimeUnit.SECONDS);
                second.get(10, TimeUnit.SECONDS);
            }
            assertEquals(1, restarted.savedJobs(f.student).size());
            restarted.removeBookmark(f.secondStudent, job);
            assertTrue(restarted.savedJobs(f.secondStudent).isEmpty());
            assertThrows(UserError.class, () -> restarted.savedJobs(f.company));
            f.jobs.submit(f.company, job, f.details());
            var hidden = restarted.savedJobs(f.student).getFirst();
            assertEquals(JobService.SavedStatus.UNAVAILABLE, hidden.status());
            assertEquals("Unavailable posting", hidden.title());
            assertThrows(UserError.class, () -> restarted.saveBookmark(f.student, job));
            f.jobs.moderate(f.admin, job, true, "");
            f.confirm(f.student, List.of(f.skill));
            var application = f.applications.apply(f.student, job);
            assertEquals(application.id(), restarted.savedJobs(f.student).getFirst().applicationId());
            f.applications.transition(f.company, application.id(), Status.SHORTLISTED);
            f.applications.transition(f.company, application.id(), Status.HIRED);
            assertEquals(JobService.SavedStatus.FILLED, restarted.savedJobs(f.student).getFirst().status());
            assertThrows(UserError.class, () -> restarted.open(f.student, job));
            restarted.removeBookmark(f.student, job);
            restarted.removeBookmark(f.student, job);
            assertTrue(restarted.savedJobs(f.student).isEmpty());
        }
    }

    @Test void bookmarksFollowEraseScopeWithoutExposingOtherStudentsInPostingExports() throws Exception {
        try (var f = new WorkflowFixture(root)) {
            long job = f.publish();
            long secondJob = f.publish();
            f.jobs.saveBookmark(f.student, job);
            f.jobs.saveBookmark(f.secondStudent, job);
            f.jobs.saveBookmark(f.secondStudent, secondJob);
            f.jobs.close(f.company, secondJob);
            assertEquals(JobService.SavedStatus.CLOSED, f.jobs.savedJobs(f.secondStudent).getFirst().status());
            var service = new WorkspaceService(f.identity, new JdbcWorkspace(f.database), f.documents);
            var studentTarget = new Target(Kind.STUDENT, Long.toString(f.identity.profile(f.student).id()));
            var jobTarget = new Target(Kind.JOB, Long.toString(job));
            var companyTarget = new Target(Kind.COMPANY, Long.toString(f.companyId));
            assertEquals(1, service.eraseImpact(f.admin, studentTarget).bookmarks());
            assertEquals(2, service.eraseImpact(f.admin, jobTarget).bookmarks());
            assertEquals(3, service.eraseImpact(f.admin, companyTarget).bookmarks());
            Path studentZip = root.resolve("student.zip");
            Path jobZip = root.resolve("job.zip");
            service.exportData(f.admin, studentTarget, studentZip);
            service.exportData(f.admin, jobTarget, jobZip);
            try (var student = new ZipFile(studentZip.toFile()); var posting = new ZipFile(jobZip.toFile())) {
                try (var data = student.getInputStream(student.getEntry("saved_jobs.txt"))) {
                    assertEquals(2, new String(data.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines().count());
                }
                assertNull(posting.getEntry("saved_jobs.txt"));
            }
            service.eraseData(f.admin, service.eraseImpact(f.admin, studentTarget));
            assertEquals(1, service.eraseImpact(f.admin, jobTarget).bookmarks());
            service.eraseData(f.admin, service.eraseImpact(f.admin, jobTarget));
            assertEquals(List.of(secondJob), f.jobs.savedJobs(f.secondStudent).stream().map(JobService.SavedJob::jobId).toList());
            service.eraseData(f.admin, service.eraseImpact(f.admin, companyTarget));
            assertTrue(f.jobs.savedJobs(f.secondStudent).isEmpty());
        }
    }
}
