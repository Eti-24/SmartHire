package smarthire.jobs;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import smarthire.WorkflowFixture;
import smarthire.applications.Application;
import smarthire.applications.ApplicationService;
import smarthire.identity.UserError;
import smarthire.storage.JdbcApplications;
import smarthire.storage.JdbcJobs;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "smarthire.integration", matches = "true")
class DeadlineIntegrationTest {
    @TempDir Path root;

    @Test void exactCutoffStopsIntakeAndDiscoveryWhileRecruitingContinues() throws Exception {
        try (var f = new WorkflowFixture(root)) {
            Instant cutoff = Instant.parse("2030-04-02T12:00:00Z");
            var before = Clock.fixed(cutoff.minusNanos(1), ZoneOffset.UTC);
            var at = Clock.fixed(cutoff, ZoneOffset.UTC);
            var after = Clock.offset(at, java.time.Duration.ofSeconds(1));
            var storage = new JdbcJobs(f.database);
            var open = new JobService(f.identity, storage, f.documents, before);
            var closed = new JobService(f.identity, storage, f.documents, at);
            var d = f.details();
            var details = new Job.Details(d.title(), d.description(), d.categoryId(), d.location(), d.salaryMin(),
                    d.salaryMax(), d.requirements(), 2, new Job.Deadline(cutoff, ZoneId.of("Asia/Dhaka")));
            long job = open.submit(f.company, 0, details);
            assertThrows(UserError.class, () -> closed.moderate(f.admin, job, true, ""));
            open.moderate(f.admin, job, true, "");
            open.saveBookmark(f.student, job);
            f.confirm(f.student, List.of(f.skill));
            var criteria = new JobSearch(d.title(), d.categoryId(), d.location(), d.salaryMax(), d.salaryMax(),
                    cutoff.minusSeconds(1), cutoff.plusSeconds(1));
            assertEquals(List.of(job), open.list(f.student, criteria).stream().map(Job::id).toList());
            var tooHigh = new JobSearch(d.title(), d.categoryId(), "", d.salaryMax().add(BigDecimal.ONE), null, null, null);
            assertTrue(open.list(f.student, tooHigh).isEmpty());
            assertTrue(closed.list(f.student, criteria).isEmpty());
            assertThrows(UserError.class, () -> closed.open(f.student, job));
            assertEquals(JobService.SavedStatus.DEADLINE_PASSED, closed.savedJobs(f.student).getFirst().status());
            var application = new ApplicationService(f.identity, new JdbcApplications(f.database), f.documents, before)
                    .apply(f.student, job);
            f.confirm(f.secondStudent, List.of(f.skill));
            for (var clock : List.of(at, after)) {
                var service = new ApplicationService(f.identity, new JdbcApplications(f.database), f.documents, clock);
                assertThrows(UserError.class, () -> service.apply(f.secondStudent, job));
            }
            var service = new ApplicationService(f.identity, new JdbcApplications(f.database), f.documents, after);
            service.transition(f.company, application.id(), Application.Status.SHORTLISTED);
            service.transition(f.company, application.id(), Application.Status.HIRED);
            assertEquals(1, closed.open(f.company, job).remainingVacancies());
            assertTrue(closed.hiringHistory(f.student).stream().noneMatch(record -> record.jobId() == job));
        }
    }

    @Test void rejectsNonexistentAndAmbiguousLocalDeadlineTimes() {
        var zone = ZoneId.of("America/New_York");
        assertThrows(UserError.class, () -> Job.Deadline.of(LocalDateTime.parse("2030-03-10T02:30"), zone));
        assertThrows(UserError.class, () -> Job.Deadline.of(LocalDateTime.parse("2030-11-03T01:30"), zone));
    }
}
