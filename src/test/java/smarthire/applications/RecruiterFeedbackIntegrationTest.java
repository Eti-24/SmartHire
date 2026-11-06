package smarthire.applications;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.zip.ZipFile;
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

@EnabledIfSystemProperty(named = "smarthire.integration", matches = "true")
class RecruiterFeedbackIntegrationTest {
    @TempDir Path root;

    @Test void notesAndScorecardsAreOwnedVersionedAndFollowExactApplicationErasure() throws Exception {
        try (var f = new WorkflowFixture(root)) {
            long job = f.publish();
            var cv = f.confirm(f.student, List.of(f.skill));
            f.confirm(f.secondStudent, List.of(f.skill));
            var application = f.applications.apply(f.student, job);
            var second = f.applications.apply(f.secondStudent, job);
            var id = application.id();
            var now = Instant.now();
            var service = new ApplicationService(f.identity, new JdbcApplications(f.database), f.documents, Clock.fixed(now, ZoneOffset.UTC));
            service.transition(f.company, id, Application.Status.SHORTLISTED);
            assertThrows(UserError.class, () -> service.feedback(f.student, id));
            assertThrows(UserError.class, () -> service.feedback(f.admin, id));
            assertThrows(UserError.class, () -> service.feedback(f.foreignCompany, id));
            var notes = service.saveFeedback(f.company, id, 0, "Prepared sample", null);
            var score = new RecruiterFeedback.Scorecard(2, 4, null, 5, "Discussed the project.");
            assertThrows(UserError.class, () -> service.saveFeedback(f.company, id, notes.feedback().revision(), "", score));
            assertThrows(UserError.class, () -> service.saveFeedback(f.company, id, 0, "stale", null));
            var slot = new Interview.Slot(now.plusSeconds(60), now.plusSeconds(120), ZoneOffset.UTC, "Office");
            service.changeInterview(f.company, id, 0, Interview.Action.PROPOSE, slot);
            service.changeInterview(f.student, id, 1, Interview.Action.ACCEPT, null);
            var after = new ApplicationService(f.identity, new JdbcApplications(f.database), f.documents, Clock.fixed(now.plusSeconds(180), ZoneOffset.UTC));
            assertThrows(UserError.class, () -> after.saveFeedback(f.company, id, 1, "", new RecruiterFeedback.Scorecard(2, 0, null, 5, "")));
            after.saveFeedback(f.company, id, 1, "Prepared sample", score);
            var compared = after.compareFeedback(f.company, id, second.id());
            assertEquals(score, compared.getFirst().feedback().scorecard());
            assertNull(compared.getLast().feedback().scorecard());
            assertEquals(application.originalScore(), after.open(f.company, id).ranking().percentage());
            f.confirm(f.student, List.of(f.otherSkill));
            assertEquals(score, after.feedback(f.company, id).feedback().scorecard());
            var admin = new WorkspaceService(f.identity, new JdbcWorkspace(f.database), f.documents);
            var target = new AdminData.Target(AdminData.Kind.CV, cv.id().toString());
            Path archive = root.resolve("feedback.zip");
            admin.exportData(f.admin, target, archive);
            try (var zip = new ZipFile(archive.toFile())) {
                assertNotNull(zip.getEntry("interview_feedback.txt"));
                assertNotNull(zip.getEntry("application_notes.txt"));
            }
            var impact = admin.eraseImpact(f.admin, target);
            assertEquals(1, impact.recruiterNotes());
            assertEquals(1, impact.interviewFeedback());
            admin.eraseData(f.admin, impact);
            assertThrows(UserError.class, () -> after.feedback(f.company, id));
            assertNull(after.feedback(f.company, second.id()).feedback().scorecard());
        }
    }
}
