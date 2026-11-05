package smarthire.cv;

import java.nio.file.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import smarthire.WorkflowFixture;
import smarthire.applications.*;
import smarthire.identity.*;
import smarthire.storage.*;
import smarthire.workspace.*;
import static org.junit.jupiter.api.Assertions.*;
import static smarthire.cv.CvApproval.Status.*;

@EnabledIfSystemProperty(named = "smarthire.integration", matches = "true")
class CvApprovalIntegrationTest {
    @TempDir Path root;
    WorkflowFixture f;
    @BeforeEach void setup() { f = new WorkflowFixture(root); }
    @AfterEach void cleanup() throws Exception { if (f != null) { f.close(); } }
    private WorkspaceService workspace() { return new WorkspaceService(f.identity, new JdbcWorkspace(f.database)); }

    @Test void pendingAndLegacyVersionsFailClosedAndApprovalPersistsWithFileQualificationsAndReviewer() throws Exception {
        long before = workspace().dashboard(f.admin).counts().get("CVs awaiting approval");
        long job = f.publish(); var cv = f.confirmPending(f.student, List.of(f.skill));
        assertEquals(before + 1, workspace().dashboard(f.admin).counts().get("CVs awaiting approval"));
        assertEquals(PENDING, f.cvs.approval(f.student, cv.id()).status());
        var match = new smarthire.matching.MatchService(f.identity, f.cvs, f.jobs).open(f.student, job).match(); assertTrue(match.eligible()); assertEquals(100, match.percentage());
        assertThrows(UserError.class, () -> f.applications.apply(f.student, job));
        f.execute("DELETE FROM cv_approvals WHERE version_id=?", cv.id().toString()); // Pre-upgrade CV with no decision.
        assertEquals(PENDING, f.cvs.approval(f.student, cv.id()).status());
        assertTrue(f.approvals.list(f.admin, PENDING).stream().anyMatch(s -> s.version().equals(cv.id())));
        assertThrows(UserError.class, () -> f.applications.apply(f.student, job));
        var review = f.approvals.open(f.admin, cv.id()); assertEquals(cv.id(), review.cv().id()); assertEquals(cv.qualifications(), review.cv().qualifications()); assertEquals(cv.documentId(), review.cv().documentId()); assertTrue(review.current());
        assertArrayEquals(Files.readAllBytes(f.pdf), f.approvals.download(f.admin, cv.id()));
        f.approvals.decide(f.admin, cv.id(), APPROVED, "");
        assertEquals(before, workspace().dashboard(f.admin).counts().get("CVs awaiting approval"));
        var decision = f.cvs.approval(f.student, cv.id()); assertEquals(APPROVED, decision.status());
        assertEquals(f.identity.profile(f.admin).id(), decision.reviewerId()); assertNotNull(decision.decidedAt());
        assertEquals(decision, new CvService(f.identity, new JdbcCvs(f.database), f.documents).approval(f.student, cv.id()));
        assertEquals(cv.id(), f.applications.apply(f.student, job).cvVersion());
        assertTrue(workspace().notices(f.student).stream().anyMatch(n -> n.message().contains(cv.id().toString()) && n.message().contains("approved")));
    }

    @Test void rejectionNeedsReasonAndResubmissionIsNewPendingVersionIncludingQualificationOnlyEdits() {
        long job = f.publish(); var cv = f.confirmPending(f.student, List.of(f.skill));
        for (String reason : List.of("", "   ", "x".repeat(1001))) { assertThrows(UserError.class, () -> f.approvals.decide(f.admin, cv.id(), REJECTED, reason)); }
        assertThrows(UserError.class, () -> f.approvals.decide(f.admin, cv.id(), PENDING, ""));
        f.approvals.decide(f.admin, cv.id(), REJECTED, "Correct the education entry");
        assertEquals("Correct the education entry", f.cvs.approval(f.student, cv.id()).reason());
        assertThrows(UserError.class, () -> f.applications.apply(f.student, job));
        assertThrows(UserError.class, () -> f.approvals.decide(f.admin, cv.id(), APPROVED, ""));
        var edited = f.confirmPending(f.student, List.of(f.skill)); assertEquals(cv.documentId(), edited.documentId());
        assertEquals(PENDING, f.cvs.approval(f.student, edited.id()).status()); assertEquals(REJECTED, f.cvs.approval(f.student, cv.id()).status());
        assertThrows(UserError.class, () -> f.applications.apply(f.student, job));
        f.approvals.decide(f.admin, edited.id(), APPROVED, ""); f.applications.apply(f.student, job);
        assertTrue(workspace().notices(f.student).stream().anyMatch(n -> n.message().contains("Correct the education entry")));
    }

    @Test void unconfirmedAndCancelledDraftsDoNotRemoveApprovalOfTheCurrentVersion() {
        long job = f.publish(); var original = f.confirm(f.student, List.of(f.skill));
        try (var draft = f.cvs.upload(f.student, Path.of("src/test/resources/documents/fictional-cv.docx"))) {
            assertEquals(original.id(), f.cvs.current(f.student).orElseThrow().id());
            assertEquals(APPROVED, f.cvs.approval(f.student, original.id()).status());
            assertTrue(f.approvals.list(f.admin, PENDING).stream().noneMatch(s -> s.studentId() == original.studentId()));
        }
        assertEquals(original.id(), f.applications.apply(f.student, job).cvVersion());
    }

    @Test void replacementRemovesPermissionForNewApplicationsButPreservesPreviouslySubmittedEvidence() {
        long firstJob = f.publish(), secondJob = f.publish(); var original = f.confirm(f.student, List.of(f.skill));
        var application = f.applications.apply(f.student, firstJob);
        try (var draft = f.cvs.upload(f.student, Path.of("src/test/resources/documents/fictional-cv.docx"))) { f.cvs.confirm(f.student, draft, original.qualifications()); }
        var replacement = f.cvs.current(f.student).orElseThrow();
        assertEquals(PENDING, f.cvs.approval(f.student, replacement.id()).status());
        assertThrows(UserError.class, () -> f.applications.apply(f.student, secondJob));
        var saved = f.applications.open(f.company, application.id()); assertEquals(original.id(), saved.cv().id()); assertEquals(original.qualifications(), saved.cv().qualifications()); assertEquals(original.documentId(), saved.cv().documentId());
        assertEquals(application.originalExplanation(), saved.application().originalExplanation());
        assertDoesNotThrow(() -> f.applications.download(f.company, application.id()));
        assertEquals(APPROVED, f.cvs.approval(f.student, original.id()).status());
        f.approvals.decide(f.admin, replacement.id(), APPROVED, ""); assertEquals(replacement.id(), f.applications.apply(f.student, secondJob).cvVersion());
    }

    @Test void rolesOwnershipDisablementStaleVersionsAndMissingFilesCannotBypassReview() throws Exception {
        var old = f.confirmPending(f.student, List.of(f.skill)); var current = f.confirmPending(f.student, List.of(f.skill));
        assertFalse(f.approvals.open(f.admin, old.id()).current());
        assertThrows(UserError.class, () -> f.approvals.decide(f.admin, old.id(), APPROVED, ""));
        for (UUID forbidden : List.of(f.student, f.company, f.foreignCompany)) {
            assertThrows(UserError.class, () -> f.approvals.list(forbidden, null));
            assertThrows(UserError.class, () -> f.approvals.open(forbidden, current.id()));
            assertThrows(UserError.class, () -> f.approvals.download(forbidden, current.id()));
            assertThrows(UserError.class, () -> f.approvals.decide(forbidden, current.id(), APPROVED, ""));
        }
        assertThrows(UserError.class, () -> f.cvs.approval(f.secondStudent, current.id()));
        long studentId = current.studentId(); workspace().setActive(f.admin, studentId, false);
        assertThrows(UserError.class, () -> f.approvals.decide(f.admin, current.id(), APPROVED, ""));
        assertNotNull(f.approvals.open(f.admin, current.id())); workspace().setActive(f.admin, studentId, true);
        Files.delete(root.resolve(current.documentId() + ".pdf"));
        assertThrows(UserError.class, () -> f.approvals.decide(f.admin, current.id(), APPROVED, ""));
        assertEquals(PENDING, f.cvs.approval(f.student, current.id()).status());
        f.approvals.decide(f.admin, current.id(), REJECTED, "Original file is unavailable; upload again");
        f.execute("UPDATE users SET active=FALSE WHERE id=?", f.identity.profile(f.admin).id());
        assertThrows(UserError.class, () -> f.approvals.list(f.admin, null));
    }

    @Test void decisionAndNotificationRollbackTogetherAndDuplicateDecisionsAreRefused() {
        var cv = f.confirmPending(f.student, List.of(f.skill));
        CvApprovalRepository failing = new CvApprovalRepository() {
            @Override public <T> T transaction(Function<Store, T> work) {
                return new JdbcCvApprovals(f.database).transaction(store -> { work.apply(store); throw new UserError("Injected failure after decision and alert"); });
            }
        };
        assertThrows(UserError.class, () -> new CvApprovalService(f.identity, failing, f.documents).decide(f.admin, cv.id(), APPROVED, ""));
        assertEquals(PENDING, f.cvs.approval(f.student, cv.id()).status()); assertTrue(workspace().notices(f.student).isEmpty());
        f.approvals.decide(f.admin, cv.id(), APPROVED, "");
        assertThrows(UserError.class, () -> f.approvals.decide(f.admin, cv.id(), REJECTED, "Too late"));
        assertEquals(1, workspace().notices(f.student).size());
    }

    @Test void twoAdminsCannotCommitConflictingDecisionsForTheSameVersion() throws Exception {
        var cv = f.confirmPending(f.student, List.of(f.skill)); var start = new CountDownLatch(1);
        // Independent connections and distinct active admin accounts.
        var second = new JdbcAccounts(f.database).create(UUID.randomUUID() + "@reviewer.example", Passwords.hash("Workflow-test!21".toCharArray()), Role.ADMIN, "Farhana Rahman");
        f.accounts.add(second.id()); UUID secondAdmin = f.identity.login(second.email(), "Workflow-test!21".toCharArray());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var approve = pool.submit(() -> attempt(start, () -> f.approvals.decide(f.admin, cv.id(), APPROVED, "")));
            var reject = pool.submit(() -> attempt(start, () -> f.approvals.decide(secondAdmin, cv.id(), REJECTED, "Correct the CV")));
            start.countDown(); assertEquals(1, (approve.get(15, TimeUnit.SECONDS) ? 1 : 0) + (reject.get(15, TimeUnit.SECONDS) ? 1 : 0));
        }
        assertEquals(1, workspace().notices(f.student).size());
        assertNotEquals(PENDING, f.cvs.approval(f.student, cv.id()).status());
    }
    private static boolean attempt(CountDownLatch start, Runnable work) throws InterruptedException {
        start.await(); try { work.run(); return true; } catch (UserError e) { return false; }
    }

    @Test void approvalAndReplacementSerializeInBothCommitOrders() throws Exception {
        var old = f.confirmPending(f.student, List.of(f.skill));
        var written = new CountDownLatch(1); var commit = new CountDownLatch(1);
        CvRepository delayedCvs = new CvRepository() {
            @Override public <T> T transaction(long studentId, Function<Store, T> work) {
                return new JdbcCvs(f.database).transaction(studentId, store -> {
                    T result = work.apply(store); if (result instanceof CvProfile) { written.countDown(); await(commit); } return result;
                });
            }
        };
        var replacing = new CvService(f.identity, delayedCvs, f.documents);
        try (var draft = replacing.edit(f.student); var pool = Executors.newFixedThreadPool(2)) {
            var replacement = pool.submit(() -> replacing.confirm(f.student, draft, old.qualifications()));
            assertTrue(written.await(10, TimeUnit.SECONDS));
            var staleApproval = pool.submit(() -> assertThrows(UserError.class, () -> f.approvals.decide(f.admin, old.id(), APPROVED, "")));
            commit.countDown(); var current = replacement.get(15, TimeUnit.SECONDS); staleApproval.get(15, TimeUnit.SECONDS);
            assertEquals(PENDING, f.cvs.approval(f.student, old.id()).status()); assertEquals(PENDING, f.cvs.approval(f.student, current.id()).status());
        } finally { commit.countDown(); }

        var current = f.cvs.current(f.student).orElseThrow(); var decided = new CountDownLatch(1); var finish = new CountDownLatch(1);
        CvApprovalRepository delayedApproval = new CvApprovalRepository() {
            @Override public <T> T transaction(Function<Store, T> work) {
                return new JdbcCvApprovals(f.database).transaction(store -> { T result = work.apply(store); decided.countDown(); await(finish); return result; });
            }
        };
        try (var draft = f.cvs.edit(f.student); var pool = Executors.newFixedThreadPool(2)) {
            var approval = pool.submit(() -> new CvApprovalService(f.identity, delayedApproval, f.documents).decide(f.admin, current.id(), APPROVED, ""));
            assertTrue(decided.await(10, TimeUnit.SECONDS));
            var replacement = pool.submit(() -> f.cvs.confirm(f.student, draft, current.qualifications()));
            finish.countDown(); approval.get(15, TimeUnit.SECONDS); var next = replacement.get(15, TimeUnit.SECONDS);
            assertEquals(APPROVED, f.cvs.approval(f.student, current.id()).status()); assertEquals(PENDING, f.cvs.approval(f.student, next.id()).status());
            assertThrows(UserError.class, () -> f.applications.apply(f.student, f.publish()));
        } finally { finish.countDown(); }
    }

    @Test void applicationAndReplacementSerializeInBothCommitOrders() throws Exception {
        long job = f.publish(); var cv = f.confirm(f.student, List.of(f.skill));
        var submitted = new CountDownLatch(1); var finish = new CountDownLatch(1);
        ApplicationRepository delayedApplications = new ApplicationRepository() {
            @Override public <T> T transaction(Function<Store, T> work) {
                return new JdbcApplications(f.database).transaction(store -> { T result = work.apply(store); submitted.countDown(); await(finish); return result; });
            }
        };
        try (var draft = f.cvs.edit(f.student); var pool = Executors.newFixedThreadPool(2)) {
            var application = pool.submit(() -> new ApplicationService(f.identity, delayedApplications, f.documents).apply(f.student, job));
            assertTrue(submitted.await(10, TimeUnit.SECONDS));
            var replacement = pool.submit(() -> f.cvs.confirm(f.student, draft, cv.qualifications()));
            finish.countDown(); var saved = application.get(15, TimeUnit.SECONDS); var next = replacement.get(15, TimeUnit.SECONDS);
            assertEquals(cv.id(), saved.cvVersion()); assertEquals(PENDING, f.cvs.approval(f.student, next.id()).status());
        } finally { finish.countDown(); }

        var current = f.cvs.current(f.student).orElseThrow(); f.approvals.decide(f.admin, current.id(), APPROVED, "");
        long anotherJob = f.publish(); var written = new CountDownLatch(1); var commit = new CountDownLatch(1);
        CvRepository delayedCvs = new CvRepository() {
            @Override public <T> T transaction(long studentId, Function<Store, T> work) {
                return new JdbcCvs(f.database).transaction(studentId, store -> { T result = work.apply(store); if (result instanceof CvProfile) { written.countDown(); await(commit); } return result; });
            }
        };
        var replacing = new CvService(f.identity, delayedCvs, f.documents);
        try (var draft = replacing.edit(f.student); var pool = Executors.newFixedThreadPool(2)) {
            var replacement = pool.submit(() -> replacing.confirm(f.student, draft, current.qualifications()));
            assertTrue(written.await(10, TimeUnit.SECONDS));
            var refused = pool.submit(() -> assertThrows(UserError.class, () -> f.applications.apply(f.student, anotherJob)));
            commit.countDown(); replacement.get(15, TimeUnit.SECONDS); refused.get(15, TimeUnit.SECONDS);
            assertTrue(f.applications.list(f.student, anotherJob, null).isEmpty());
        } finally { commit.countDown(); }
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) { throw new AssertionError("Timed out waiting to commit"); } }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}
