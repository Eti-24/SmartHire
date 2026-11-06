package smarthire.workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import smarthire.WorkflowFixture;
import smarthire.identity.UserError;
import smarthire.storage.JdbcWorkspace;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "smarthire.integration", matches = "true")
class AdminActivityIntegrationTest {
    @TempDir Path root;

    @Test void auditSurvivesEraseAndSeparatesFilesystemOutcomeFromDatabaseCommit() throws Exception {
        try (var f = new WorkflowFixture(root)) {
            var service = new WorkspaceService(f.identity, new JdbcWorkspace(f.database), f.documents);
            String job = Long.toString(f.publish());
            var target = new AdminData.Target(AdminData.Kind.JOB, job);
            assertThrows(UserError.class, () -> events(service, f, f.student, job, AdminActivity.Action.EXPORT));
            Path archive = root.resolve("private-name.zip");
            assertTrue(service.exportData(f.admin, target, archive).auditOutcomeRecorded());
            assertThrows(UserError.class, () -> service.exportData(f.admin, target, archive));
            assertEquals(List.of("FAILED", "STARTED", "SAVED", "STARTED"), outcomes(service, f, job));
            String unresolvedJob = Long.toString(f.publish());
            var unresolved = new AdminData.Target(AdminData.Kind.JOB, unresolvedJob);
            String constraint = "fail_smart_hire_export_outcome";
            f.execute("ALTER TABLE admin_activity ADD CONSTRAINT " + constraint + " CHECK (target_kind<>'JOB'"
                    + " OR target_id<>'" + unresolvedJob + "' OR outcome<>'SAVED')");
            try {
                Path second = root.resolve("still-saved.zip");
                assertFalse(service.exportData(f.admin, unresolved, second).auditOutcomeRecorded());
                assertTrue(Files.isRegularFile(second));
            } finally { f.execute("ALTER TABLE admin_activity DROP CHECK " + constraint); }
            assertEquals("OUTCOME UNRESOLVED", outcomes(service, f, unresolvedJob).getFirst());
            service.eraseData(f.admin, service.eraseImpact(f.admin, target));
            assertEquals("ERASED", events(service, f, f.admin, job, AdminActivity.Action.ERASE).getFirst().change().outcome());
            assertTrue(events(service, f, f.admin, job, AdminActivity.Action.EXPORT).stream()
                    .noneMatch(entry -> entry.toString().contains("private-name")));
        }
    }

    @Test void rejectedDecisionsAndUnchangedAccountAccessDoNotCreateEvents() throws Exception {
        try (var f = new WorkflowFixture(root)) {
            var service = new WorkspaceService(f.identity, new JdbcWorkspace(f.database), f.documents);
            long studentId = f.identity.profile(f.student).id();
            String target = Long.toString(studentId);
            service.setActive(f.admin, studentId, true);
            assertTrue(events(service, f, f.admin, target, AdminActivity.Action.ACCOUNT_ACCESS).isEmpty());
            service.setActive(f.admin, studentId, false);
            service.setActive(f.admin, studentId, false);
            assertEquals(List.of("DISABLED"), events(service, f, f.admin, target, AdminActivity.Action.ACCOUNT_ACCESS).stream()
                    .map(entry -> entry.change().outcome()).toList());
            long job = f.jobs.submit(f.company, 0, f.details());
            assertThrows(UserError.class, () -> f.jobs.moderate(f.admin, job, false, ""));
            assertTrue(events(service, f, f.admin, Long.toString(job), AdminActivity.Action.JOB_MODERATION).isEmpty());
            f.jobs.moderate(f.admin, job, false, "Private moderation reason");
            var event = events(service, f, f.admin, Long.toString(job), AdminActivity.Action.JOB_MODERATION).getFirst();
            assertEquals("REJECTED", event.change().outcome());
            assertFalse(event.toString().contains("Private moderation reason"));
        }
    }

    private static List<String> outcomes(WorkspaceService service, WorkflowFixture f, String job) {
        return events(service, f, f.admin, job, AdminActivity.Action.EXPORT).stream().map(entry -> entry.change().outcome()).toList();
    }

    private static List<AdminActivity.Entry> events(WorkspaceService service, WorkflowFixture f, java.util.UUID session,
            String id, AdminActivity.Action action) {
        return service.activity(session, new AdminActivity.Filter(action, id, null, null, null)).entries();
    }
}
