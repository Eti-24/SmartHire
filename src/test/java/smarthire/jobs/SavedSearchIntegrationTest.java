package smarthire.jobs;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import smarthire.WorkflowFixture;
import smarthire.identity.UserError;
import smarthire.storage.JdbcWorkspace;
import smarthire.workspace.AdminData;
import smarthire.workspace.WorkspaceService;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "smarthire.integration", matches = "true")
class SavedSearchIntegrationTest {
    @TempDir Path root;

    @Test void editRestartPauseResumeAndDeletePreserveCriteriaAndOwnership() throws Exception {
        try (var f = new WorkflowFixture(root)) {
            var restarted = new JobService(f.identity, new smarthire.storage.JdbcJobs(f.database), f.documents);
            var details = f.details();
            var criteria = new JobSearch(details.title(), details.categoryId(), details.location(),
                    details.salaryMin().setScale(2), details.salaryMax().setScale(2), java.time.Instant.parse("2026-01-01T00:00:00Z"),
                    java.time.Instant.parse("2030-01-01T00:00:00Z"));
            f.jobs.saveSearch(f.student, new SavedSearch(0, "Original", criteria, true));
            var saved = restarted.savedSearches(f.student).getFirst();
            assertEquals(criteria, saved.criteria());
            assertTrue(saved.active());
            var edited = new JobSearch("Edited title", null, "Remote", null,
                    new java.math.BigDecimal("45000.00"), null, null);
            restarted.saveSearch(f.student, new SavedSearch(saved.id(), "Edited", edited, false));
            var updated = f.jobs.savedSearches(f.student).getFirst();
            assertEquals("Edited", updated.name());
            assertEquals(edited, updated.criteria());
            assertFalse(updated.active());
            restarted.deleteSearch(f.secondStudent, saved.id());
            assertEquals(1, restarted.savedSearches(f.student).size());
            assertThrows(UserError.class, () -> restarted.savedSearches(f.company));
            assertThrows(UserError.class, () -> restarted.saveSearch(f.student, new SavedSearch(0, " ", edited, true)));
            restarted.saveSearch(f.student, new SavedSearch(saved.id(), "Edited", edited, true));
            assertTrue(f.jobs.savedSearches(f.student).getFirst().active());
            restarted.deleteSearch(f.student, saved.id());
            assertTrue(f.jobs.savedSearches(f.student).isEmpty());
        }
    }

    @Test void matchesOneNoticePerStudentAndJobAcrossSearchesAndApprovals() throws Exception {
        try (var f = new WorkflowFixture(root)) {
            var workspace = new WorkspaceService(f.identity, new JdbcWorkspace(f.database), f.documents);
            var d = f.details();
            var criteria = new JobSearch(d.title(), d.categoryId(), d.location(), d.salaryMax(), d.salaryMax(), null, null);
            f.jobs.saveSearch(f.student, new SavedSearch(0, "First", criteria, true));
            f.jobs.saveSearch(f.student, new SavedSearch(0, "Second", criteria, true));
            f.jobs.saveSearch(f.secondStudent, new SavedSearch(0, "Paused", criteria, false));
            var first = f.jobs.savedSearches(f.student).getFirst();
            assertThrows(UserError.class, () -> f.jobs.saveSearch(f.secondStudent, first));
            long job = f.publish();
            assertTrue(f.jobs.list(f.student, first.criteria()).stream().anyMatch(post -> post.id() == job));
            var noticeId = workspace.notices(f.student).stream().filter(n -> Long.valueOf(job).equals(n.matchedJobId()))
                    .findFirst().orElseThrow().id();
            f.jobs.submit(f.company, job, d);
            assertThrows(UserError.class, () -> f.jobs.open(f.student, job));
            assertNull(workspace.notices(f.student).stream().filter(n -> n.id().equals(noticeId)).findFirst().orElseThrow().matchedJobId());
            f.jobs.moderate(f.admin, job, true, "");
            var notices = workspace.notices(f.student).stream().filter(n -> Long.valueOf(job).equals(n.matchedJobId())).toList();
            assertEquals(1, notices.size());
            assertFalse(notices.getFirst().message().contains(d.title()));
            assertTrue(workspace.notices(f.secondStudent).stream().noneMatch(n -> Long.valueOf(job).equals(n.matchedJobId())));
            f.jobs.deleteSearch(f.student, first.id());
            assertEquals(1, f.jobs.savedSearches(f.student).size());
            assertTrue(workspace.notices(f.student).stream().anyMatch(n -> n.id().equals(notices.getFirst().id())));
            var target = new AdminData.Target(AdminData.Kind.JOB, Long.toString(job));
            workspace.eraseData(f.admin, workspace.eraseImpact(f.admin, target));
            assertTrue(workspace.notices(f.student).stream().noneMatch(n -> n.id().equals(notices.getFirst().id())));
            assertEquals(1, f.jobs.savedSearches(f.student).size());
        }
    }

    @Test void pausingResumingAndDeletingControlFutureApprovalAlerts() throws Exception {
        try (var f = new WorkflowFixture(root)) {
            var workspace = new WorkspaceService(f.identity, new JdbcWorkspace(f.database), f.documents);
            f.jobs.saveSearch(f.student, new SavedSearch(0, "All opportunities", new JobSearch("", null), false));
            var saved = f.jobs.savedSearches(f.student).getFirst();
            long paused = f.publish();
            assertTrue(workspace.notices(f.student).stream().noneMatch(n -> Long.valueOf(paused).equals(n.matchedJobId())));
            f.jobs.saveSearch(f.student, new SavedSearch(saved.id(), saved.name(), saved.criteria(), true));
            long active = f.publish();
            assertEquals(1, workspace.notices(f.student).stream().filter(n -> Long.valueOf(active).equals(n.matchedJobId())).count());
            f.jobs.deleteSearch(f.student, saved.id());
            long deleted = f.publish();
            assertTrue(workspace.notices(f.student).stream().noneMatch(n -> Long.valueOf(deleted).equals(n.matchedJobId())));
        }
    }
}
