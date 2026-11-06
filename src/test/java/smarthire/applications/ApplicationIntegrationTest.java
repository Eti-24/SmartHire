package smarthire.applications;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import smarthire.WorkflowFixture;
import smarthire.identity.UserError;
import smarthire.jobs.*;
import smarthire.matching.MatchService;
import smarthire.storage.*;
import smarthire.workspace.WorkspaceService;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "smarthire.integration", matches = "true")
class ApplicationIntegrationTest {
    @TempDir Path root;
    WorkflowFixture f;
    @BeforeEach void setup() { f = new WorkflowFixture(root); }
    @AfterEach void cleanup() throws Exception { if (f != null) { f.close(); } }

    @Test void submittedEvidenceOriginalAssessmentAndContactDetailsSurviveReplacementRestartAndClosure() throws Exception {
        long job = f.publish(); var cv = f.confirm(f.student, List.of("alias-" + f.skill));
        var a = f.applications.apply(f.student, job);
        assertEquals(cv.id(), a.cvVersion()); assertEquals(100, a.originalScore());
        var oldProfile = a.submittedProfile();
        f.identity.saveProfile(f.student, "Changed name", "123", "Changed university", "Changed profile");
        try (var draft = f.cvs.upload(f.student, Path.of("src/test/resources/documents/fictional-cv.docx"))) {
            f.cvs.confirm(f.student, draft, smarthire.cv.CvProfile.Qualifications.empty());
        }
        f.jobs.close(f.company, job);
        var restarted = new ApplicationService(f.identity, new JdbcApplications(f.database), f.documents);
        var saved = restarted.open(f.company, a.id());
        assertEquals(oldProfile, saved.application().submittedProfile());
        assertEquals(a.originalExplanation(), saved.application().originalExplanation());
        assertEquals(cv.qualifications(), saved.cv().qualifications());
        assertEquals(100, saved.ranking().percentage());
        assertArrayEquals(Files.readAllBytes(f.pdf), restarted.download(f.company, a.id()));
        f.execute("UPDATE users SET active=FALSE WHERE id=?", f.companyId);
        assertEquals(a.id(), restarted.open(f.student, a.id()).application().id());
        assertThrows(UserError.class, () -> restarted.download(f.company, a.id()));
    }

    @Test void duplicateWithdrawalAndTerminalStatesCannotBypassUniquenessOrFrozenRequirements() {
        long job = f.publish(); f.confirm(f.student, List.of(f.skill));
        var a = f.applications.apply(f.student, job);
        assertThrows(UserError.class, () -> f.applications.apply(f.student, job));
        assertThrows(UserError.class, () -> f.applications.transition(f.company, a.id(), Application.Status.HIRED));
        f.applications.transition(f.company, a.id(), Application.Status.SHORTLISTED);
        f.applications.transition(f.student, a.id(), Application.Status.WITHDRAWN);
        assertThrows(UserError.class, () -> f.applications.apply(f.student, job));
        assertThrows(UserError.class, () -> f.jobs.submit(f.company, job, f.details()));
        assertThrows(UserError.class, () -> f.applications.transition(f.company, a.id(), Application.Status.HIRED));
        assertEquals(Application.Status.WITHDRAWN, f.applications.open(f.student, a.id()).application().status());
        f.confirm(f.secondStudent, List.of(f.skill)); var second = f.applications.apply(f.secondStudent, job);
        f.applications.transition(f.company, second.id(), Application.Status.SHORTLISTED);
        f.applications.transition(f.company, second.id(), Application.Status.HIRED);
        assertThrows(UserError.class, () -> f.applications.transition(f.secondStudent, second.id(), Application.Status.WITHDRAWN));
        assertThrows(UserError.class, () -> f.applications.transition(f.company, second.id(), Application.Status.REJECTED));
    }

    @Test void rolesOwnershipEligibilityMissingReviewAndMissingBytesAreEnforced() throws Exception {
        long job = f.publish();
        assertThrows(UserError.class, () -> f.applications.apply(f.student, job));
        f.confirm(f.student, List.of(f.otherSkill));
        assertThrows(UserError.class, () -> f.applications.apply(f.student, job));
        var cv = f.confirm(f.student, List.of(f.skill));
        var a = f.applications.apply(f.student, job);
        for (UUID forbidden : List.of(f.secondStudent, f.foreignCompany, f.admin)) {
            assertThrows(UserError.class, () -> f.applications.open(forbidden, a.id()));
            assertThrows(UserError.class, () -> f.applications.download(forbidden, a.id()));
            assertThrows(UserError.class, () -> f.applications.transition(forbidden, a.id(), Application.Status.REJECTED));
        }
        assertTrue(f.applications.list(f.foreignCompany, null, null).isEmpty());
        assertThrows(UserError.class, () -> f.applications.apply(f.company, job));
        Files.delete(root.resolve(cv.documentId() + ".pdf"));
        assertThrows(UserError.class, () -> f.applications.download(f.company, a.id()));
        long next = f.publish(); assertThrows(UserError.class, () -> f.applications.apply(f.student, next));
        assertEquals(a.id(), f.applications.open(f.company, a.id()).application().id());
    }

    @Test void pendingClosedUnverifiedAndDisabledAccountsCannotSubmitAndFiltersRespectOwnership() throws Exception {
        f.confirm(f.student, List.of(f.skill));
        long pending = f.jobs.submit(f.company, 0, f.details());
        assertThrows(UserError.class, () -> f.applications.apply(f.student, pending));
        long job = f.publish(); f.jobs.verify(f.admin, f.companyId, false);
        assertThrows(UserError.class, () -> f.applications.apply(f.student, job));
        f.jobs.verify(f.admin, f.companyId, true);
        var a = f.applications.apply(f.student, job);
        f.applications.transition(f.company, a.id(), Application.Status.REJECTED);
        assertEquals(1, f.applications.list(f.company, job, Application.Status.REJECTED).size());
        assertTrue(f.applications.list(f.company, job, Application.Status.APPLIED).isEmpty());
        f.jobs.close(f.company, job); f.confirm(f.secondStudent, List.of(f.skill));
        assertThrows(UserError.class, () -> f.applications.apply(f.secondStudent, job));
        f.execute("UPDATE users SET active=FALSE WHERE id=?", f.identity.profile(f.student).id());
        assertThrows(UserError.class, () -> f.applications.list(f.student, null, null));
        assertThrows(UserError.class, () -> f.applications.transition(f.student, a.id(), Application.Status.WITHDRAWN));
    }

    @Test void failedSubmissionRollsBackAndDoesNotFreezeThePosting() {
        long job = f.publish(); f.confirm(f.student, List.of(f.skill));
        ApplicationRepository failing = new ApplicationRepository() {
            @Override public <T> T transaction(Function<Store, T> work) {
                return new JdbcApplications(f.database).transaction(store -> { work.apply(store); throw new UserError("Injected rollback"); });
            }
        };
        assertThrows(UserError.class, () -> new ApplicationService(f.identity, failing, f.documents).apply(f.student, job));
        assertTrue(f.applications.list(f.student, job, null).isEmpty());
        f.jobs.submit(f.company, job, f.details());
        assertEquals(Job.Status.PENDING, f.jobs.open(f.company, job).status());
    }

    @Test void concurrentDuplicateSubmissionsCommitExactlyOneApplication() throws Exception {
        long job = f.publish(); f.confirm(f.student, List.of(f.skill));
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> apply = () -> {
                await(start);
                try { f.applications.apply(f.student, job); return true; }
                catch (UserError e) { assertTrue(e.getMessage().contains("already applied")); return false; }
            };
            var first = pool.submit(apply); var second = pool.submit(apply); start.countDown();
            assertNotEquals(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertEquals(1, f.applications.list(f.company, job, null).size());
        }
    }

    @Test void filledPostingsLeaveStudentDiscoveryAndAppearInRoleScopedHistory() {
        long job = f.publish();
        long closed = f.publish();
        f.jobs.close(f.company, closed);
        f.confirm(f.student, List.of(f.skill));
        f.confirm(f.secondStudent, List.of(f.skill));
        var matches = new MatchService(f.identity, f.cvs, f.jobs);
        var workspace = new WorkspaceService(f.identity, new JdbcWorkspace(f.database));
        var application = f.applications.apply(f.student, job);
        f.applications.transition(f.company, application.id(), Application.Status.SHORTLISTED);
        assertEquals(job, matches.open(f.secondStudent, job).job().id());
        assertTrue(f.jobs.hiringHistory(f.company).isEmpty());
        long availableBefore = workspace.dashboard(f.secondStudent).counts().get("Available jobs");

        f.applications.transition(f.company, application.id(), Application.Status.HIRED);

        var restartedJobs = new JobService(f.identity, new JdbcJobs(f.database), f.documents);
        assertTrue(restartedJobs.list(f.secondStudent, "", f.category).isEmpty());
        assertFalse(matches.recommend(f.secondStudent).jobs().stream().anyMatch(match -> match.job().id() == job));
        assertThrows(UserError.class, () -> matches.open(f.secondStudent, job));
        assertThrows(UserError.class, () -> restartedJobs.circular(f.secondStudent, job));
        assertTrue(assertThrows(UserError.class, () -> f.applications.apply(f.secondStudent, job)).getMessage().contains("filled"));
        assertEquals(availableBefore - 1, workspace.dashboard(f.secondStudent).counts().get("Available jobs"));

        var companyHistory = restartedJobs.hiringHistory(f.company);
        assertEquals(List.of(job), companyHistory.stream().map(Job.HiringRecord::jobId).toList());
        assertTrue(restartedJobs.hiringHistory(f.secondStudent).contains(companyHistory.getFirst()));
        assertTrue(restartedJobs.hiringHistory(f.foreignCompany).isEmpty());
        assertThrows(UserError.class, () -> restartedJobs.hiringHistory(f.admin));
        assertEquals(Application.Status.HIRED, f.applications.open(f.student, application.id()).application().status());
    }

    @Test void simultaneousHiresFillOnlyOneVacancyAndLeaveTheOtherApplicationShortlisted() throws Exception {
        long job = f.publish();
        f.confirm(f.student, List.of(f.skill));
        f.confirm(f.secondStudent, List.of(f.skill));
        var first = f.applications.apply(f.student, job);
        var second = f.applications.apply(f.secondStudent, job);
        for (var application : List.of(first, second)) {
            f.applications.transition(f.company, application.id(), Application.Status.SHORTLISTED);
        }

        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Function<UUID, Boolean> hire = id -> {
                await(start);
                try { f.applications.transition(f.company, id, Application.Status.HIRED); return true; }
                catch (UserError error) { assertTrue(error.getMessage().contains("filled")); return false; }
            };
            var firstHire = pool.submit(() -> hire.apply(first.id()));
            var secondHire = pool.submit(() -> hire.apply(second.id()));
            start.countDown();
            assertNotEquals(firstHire.get(15, TimeUnit.SECONDS), secondHire.get(15, TimeUnit.SECONDS));
        }
        assertEquals(1, f.applications.list(f.company, job, Application.Status.HIRED).size());
        assertEquals(1, f.applications.list(f.company, job, Application.Status.SHORTLISTED).size());
    }

    @Test void submissionCommittingFirstFreezesAConcurrentEditOnAnotherConnection() throws Exception {
        long job = f.publish(); f.confirm(f.student, List.of(f.skill));
        var written = new CountDownLatch(1); var commit = new CountDownLatch(1);
        ApplicationRepository delayed = new ApplicationRepository() {
            @Override public <T> T transaction(Function<Store, T> work) {
                return new JdbcApplications(f.database).transaction(store -> { T result = work.apply(store); written.countDown(); await(commit); return result; });
            }
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var submission = pool.submit(() -> new ApplicationService(f.identity, delayed, f.documents).apply(f.student, job));
            try {
                assertTrue(written.await(10, TimeUnit.SECONDS));
                var edit = pool.submit(() -> assertThrows(UserError.class, () -> f.jobs.submit(f.company, job, f.details())));
                commit.countDown(); submission.get(15, TimeUnit.SECONDS); edit.get(15, TimeUnit.SECONDS);
                assertEquals(1, f.applications.list(f.company, job, null).size());
            } finally { commit.countDown(); }
        }
    }

    @Test void editCommittingFirstMakesAConcurrentSubmissionFailAndLeavesNoApplication() throws Exception {
        long job = f.publish(); f.confirm(f.student, List.of(f.skill));
        var written = new CountDownLatch(1); var commit = new CountDownLatch(1);
        JobRepository delayed = new JobRepository() {
            @Override public <T> T transaction(Function<Store, T> work) {
                return new JdbcJobs(f.database).transaction(store -> { T result = work.apply(store); written.countDown(); await(commit); return result; });
            }
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var edit = pool.submit(() -> new JobService(f.identity, delayed).submit(f.company, job, f.details()));
            try {
                assertTrue(written.await(10, TimeUnit.SECONDS));
                var apply = pool.submit(() -> assertThrows(UserError.class, () -> f.applications.apply(f.student, job)));
                commit.countDown(); edit.get(15, TimeUnit.SECONDS); apply.get(15, TimeUnit.SECONDS);
                assertTrue(f.applications.list(f.company, job, null).isEmpty());
                assertEquals(Job.Status.PENDING, f.jobs.open(f.company, job).status());
            } finally { commit.countDown(); }
        }
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(15, TimeUnit.SECONDS)) { throw new AssertionError("Timed out waiting for test transaction"); } }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}
