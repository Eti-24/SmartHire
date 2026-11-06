package smarthire.workspace;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import smarthire.WorkflowFixture;
import smarthire.applications.Application;
import smarthire.applications.ApplicationService;
import smarthire.identity.UserError;
import smarthire.jobs.Job;
import smarthire.storage.JdbcApplications;
import smarthire.storage.JdbcWorkspace;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "smarthire.integration", matches = "true")
class RecruitmentReportIntegrationTest {
    @TempDir Path root;

    @Test void reportsScopedCohortAndCurrentCapacityWithLegacyDates() throws Exception {
        try (var f = new WorkflowFixture(root)) {
            var workspace = new WorkspaceService(f.identity, new JdbcWorkspace(f.database), f.documents);
            var d = f.details();
            long job = f.publish(new Job.Details(d.title(), d.description(), d.categoryId(), d.location(),
                    d.salaryMin(), d.salaryMax(), d.requirements(), 2));
            Instant from = Instant.parse("2030-04-02T00:00:00Z");
            f.confirm(f.student, List.of(f.skill));
            f.confirm(f.secondStudent, List.of(f.skill));
            var first = applications(f, from).apply(f.student, job);
            var second = applications(f, from.plusSeconds(3600)).apply(f.secondStudent, job);
            applications(f, from.plusSeconds(1800)).transition(f.company, first.id(), Application.Status.SHORTLISTED);
            applications(f, from.plusSeconds(7200)).transition(f.company, first.id(), Application.Status.HIRED);
            applications(f, from.plusSeconds(7200)).transition(f.secondStudent, second.id(), Application.Status.WITHDRAWN);
            var filter = new RecruitmentReport.Filter(from, from.plusSeconds(7200), job, f.category);
            var report = workspace.recruitmentReport(f.company, filter);
            assertEquals(report, workspace.recruitmentReport(f.admin, filter));
            assertEquals(2, report.submissions());
            assertEquals(1, report.hired());
            assertEquals(1, report.shortlistSamples());
            assertEquals(1800, report.averageShortlistSeconds().intValueExact());
            assertEquals(1, report.hireSamples());
            assertEquals(7200, report.averageHireSeconds().intValueExact());
            assertEquals(1, report.filled());
            assertEquals(2, report.vacancies());
            assertEquals(0, workspace.recruitmentReport(f.foreignCompany, filter).vacancies());
            assertThrows(UserError.class, () -> workspace.recruitmentReport(f.student, filter));
            var prior = workspace.recruitmentReport(f.company, new RecruitmentReport.Filter(from.minusSeconds(1), from, job, null));
            assertEquals(0, prior.submissions());
            assertEquals(1, prior.filled());
            f.execute("DELETE FROM application_status_events WHERE application_id=?", first.id().toString());
            f.execute("UPDATE applications SET hired_at=NULL WHERE id=?", first.id().toString());
            var legacy = workspace.recruitmentReport(f.company, filter);
            assertEquals(1, legacy.hired());
            assertEquals(0, legacy.shortlistSamples());
            assertEquals(0, legacy.hireSamples());
            assertNull(legacy.averageHireSeconds());
        }
    }

    private static ApplicationService applications(WorkflowFixture f, Instant at) {
        return new ApplicationService(f.identity, new JdbcApplications(f.database), f.documents, Clock.fixed(at, ZoneOffset.UTC));
    }
}
