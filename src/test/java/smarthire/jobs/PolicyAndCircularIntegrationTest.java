package smarthire.jobs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import smarthire.WorkflowFixture;
import smarthire.identity.UserError;
import smarthire.matching.*;
import smarthire.storage.*;
import static org.junit.jupiter.api.Assertions.*;
import static smarthire.jobs.Job.*;
import static smarthire.matching.ScoringPolicy.Category.*;

@EnabledIfSystemProperty(named = "smarthire.integration", matches = "true")
class PolicyAndCircularIntegrationTest {
    @TempDir Path root;
    WorkflowFixture f;
    @BeforeEach void setup() { f = new WorkflowFixture(root); }
    @AfterEach void cleanup() throws Exception { if (f != null) { f.close(); } }

    private ScoringPolicy activate(Map<ScoringPolicy.Category, Integer> weights) {
        var policy = f.jobs.activatePolicy(f.admin, weights); f.policies.add(policy.version()); return policy;
    }

    @Test void policyRejectsNegativeZeroAndIncompatibleOpenAndClosedApplicantJobsWithoutChangingCurrent() {
        long job = f.publish();
        String previous = f.jobs.scoringPolicy(f.admin).version();
        assertThrows(UserError.class, () -> f.jobs.activatePolicy(f.admin, Map.of(SKILLS, -1, EDUCATION, 1)));
        assertThrows(UserError.class, () -> f.jobs.activatePolicy(f.admin, Map.of(SKILLS, 0)));
        var rejected = assertThrows(UserError.class, () -> f.jobs.activatePolicy(f.admin, Map.of(EDUCATION, 1)));
        assertTrue(rejected.getMessage().contains(Long.toString(job)));
        assertEquals(previous, f.jobs.scoringPolicy(f.admin).version());
        f.confirm(f.student, List.of(f.skill)); f.applications.apply(f.student, job); f.jobs.close(f.company, job);
        assertThrows(UserError.class, () -> f.jobs.activatePolicy(f.admin, Map.of(EDUCATION, 1)));
        assertEquals(previous, f.jobs.scoringPolicy(f.admin).version());
        for (UUID actor : List.of(f.company, f.student)) { assertThrows(UserError.class, () -> f.jobs.activatePolicy(actor, Map.of(SKILLS, 1))); }
    }

    @Test void approvalUsesActivePolicyAndRecalculatedRankingUsesOnePolicyAcrossOriginalSubmissions() {
        long pending = f.jobs.submit(f.company, 0, f.details());
        activate(Map.of(EDUCATION, 1));
        assertThrows(UserError.class, () -> f.jobs.moderate(f.admin, pending, true, ""));
        activate(ScoringPolicy.initial().weights());
        var requirements = List.of(new Requirement(Kind.SKILL, f.skill, 0, Priority.REQUIRED, false), new Requirement(Kind.DEGREE, "BACHELOR", 0, Priority.REQUIRED, false));
        long job = f.publish(f.details(requirements));
        f.confirm(f.student, List.of()); var first = f.applications.apply(f.student, job);
        assertEquals(100.0 / 3, first.originalScore(), 1e-10);
        var next = activate(Map.of(SKILLS, 10, EDUCATION, 90, EXPERIENCE, 1, PROJECTS, 1, CERTIFICATIONS, 1));
        f.confirm(f.secondStudent, List.of()); var second = f.applications.apply(f.secondStudent, job);
        assertEquals(90, second.originalScore(), 1e-10);
        f.confirm(f.student, List.of(f.skill));
        var ranked = f.applications.list(f.company, job, null);
        assertEquals(2, ranked.size());
        assertTrue(ranked.stream().allMatch(entry -> entry.ranking().policyVersion().equals(next.version())));
        assertTrue(ranked.stream().allMatch(entry -> Math.abs(entry.ranking().percentage() - 90) < 1e-10));
        assertEquals(first.id(), ranked.getFirst().application().id());
        assertEquals(first.originalExplanation(), f.applications.open(f.student, first.id()).application().originalExplanation());
        assertEquals(first.originalPolicy(), f.applications.open(f.student, first.id()).application().originalPolicy());
        assertNotEquals(first.originalPolicy(), second.originalPolicy());
        var restarted = new JobService(f.identity, new JdbcJobs(f.database), f.documents);
        assertEquals(next, restarted.scoringPolicy(f.student));
        assertEquals(next.version(), new MatchService(f.identity, f.cvs, restarted).open(f.student, job).match().policyVersion());
    }

    @Test void zeroWeightedMandatoryRequirementStillBlocksApplicationDespitePerfectScore() {
        long job = f.publish(f.details(List.of(new Requirement(Kind.SKILL, f.skill, 0, Priority.REQUIRED, true),
                new Requirement(Kind.DEGREE, "BACHELOR", 0, Priority.REQUIRED, false))));
        activate(Map.of(EDUCATION, 1)); f.confirm(f.student, List.of());
        var result = new MatchService(f.identity, f.cvs, f.jobs).open(f.student, job);
        assertEquals(100, result.match().percentage()); assertFalse(result.match().eligible());
        assertThrows(UserError.class, () -> f.applications.apply(f.student, job));
    }

    @Test void circularsRequireOwnershipApprovalAndCurrentVisibilityAndChangesFreezeWithApplications() throws Exception {
        long job = f.jobs.submit(f.company, 0, f.details());
        assertThrows(UserError.class, () -> f.jobs.attachCircular(f.foreignCompany, job, f.pdf));
        assertThrows(UserError.class, () -> f.jobs.attachCircular(f.student, job, f.pdf));
        f.jobs.attachCircular(f.company, job, f.pdf);
        assertTrue(f.jobs.circular(f.company, job).isPresent()); assertTrue(f.jobs.circular(f.admin, job).isPresent());
        assertThrows(UserError.class, () -> f.jobs.circular(f.student, job));
        assertThrows(UserError.class, () -> f.jobs.downloadCircular(f.foreignCompany, job));
        f.jobs.moderate(f.admin, job, true, "");
        assertArrayEquals(Files.readAllBytes(f.pdf), f.jobs.downloadCircular(f.student, job));
        f.jobs.attachCircular(f.company, job, Path.of("src/test/resources/documents/fictional-cv.docx"));
        assertEquals(Status.PENDING, f.jobs.open(f.company, job).status());
        assertThrows(UserError.class, () -> f.jobs.downloadCircular(f.student, job));
        f.jobs.moderate(f.admin, job, true, ""); f.confirm(f.student, List.of(f.skill)); f.applications.apply(f.student, job);
        assertThrows(UserError.class, () -> f.jobs.attachCircular(f.company, job, f.pdf));
        f.jobs.close(f.company, job);
        assertThrows(UserError.class, () -> f.jobs.downloadCircular(f.student, job));
        assertTrue(f.jobs.downloadCircular(f.company, job).length > 0);
    }

    @Test void circularParsingAndTransactionFailurePreservePriorAttachmentAndDoNotLeaveCommittedNewReference() throws Exception {
        long job = f.jobs.submit(f.company, 0, f.details()); f.jobs.attachCircular(f.company, job, f.pdf);
        var original = f.jobs.circular(f.company, job).orElseThrow();
        assertThrows(UserError.class, () -> f.jobs.attachCircular(f.company, job, Path.of("src/test/resources/documents/textless.pdf")));
        JobRepository failing = new JobRepository() {
            @Override public <T> T transaction(java.util.function.Function<Store, T> work) {
                return new JdbcJobs(f.database).transaction(store -> { T result = work.apply(store); if (store.circular(job).map(d -> !d.id().equals(original.id())).orElse(false)) { throw new UserError("Injected rollback"); } return result; });
            }
        };
        assertThrows(UserError.class, () -> new JobService(f.identity, failing, f.documents).attachCircular(f.company, job, Path.of("src/test/resources/documents/fictional-cv.docx")));
        assertEquals(original.id(), f.jobs.circular(f.company, job).orElseThrow().id());
        assertArrayEquals(Files.readAllBytes(f.pdf), f.jobs.downloadCircular(f.company, job));
        var suggestions = f.jobs.suggest(f.company, original.text());
        assertTrue(suggestions.requirements().stream().allMatch(r -> !r.mandatory()));
        assertThrows(UserError.class, () -> f.jobs.suggest(f.student, original.text()));
        Files.delete(root.resolve(original.id() + ".pdf"));
        assertThrows(UserError.class, () -> f.jobs.downloadCircular(f.company, job));
    }

    @Test void comparisonUsesSameReviewedCvAndPolicyKeepsSimilaritySeparateAndRejectsUnavailablePostings() {
        long first = f.publish(), second = f.publish(); f.confirm(f.student, List.of(f.skill));
        var matches = new MatchService(f.identity, f.cvs, f.jobs);
        var comparison = matches.compare(f.student, first, second);
        assertEquals(comparison.first().cvVersion(), comparison.second().cvVersion());
        assertEquals(comparison.first().match().policyVersion(), comparison.second().match().policyVersion());
        assertEquals(100, comparison.first().match().percentage());
        assertTrue(comparison.firstTextSimilarity() >= 0 && comparison.firstTextSimilarity() <= 1);
        assertThrows(UserError.class, () -> matches.compare(f.student, first, first));
        assertThrows(UserError.class, () -> matches.compare(f.company, first, second));
        f.jobs.close(f.company, second);
        assertThrows(UserError.class, () -> matches.compare(f.student, first, second));
    }

    @Test void concurrentApprovalAndPolicyActivationCannotPublishAnUnscorableJobInEitherOrder() throws Exception {
        long first = f.jobs.submit(f.company, 0, f.details());
        var written = new java.util.concurrent.CountDownLatch(1); var commit = new java.util.concurrent.CountDownLatch(1);
        JobRepository delayed = delayed(written, commit);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var approval = pool.submit(() -> new JobService(f.identity, delayed).moderate(f.admin, first, true, ""));
            try {
                assertTrue(written.await(10, java.util.concurrent.TimeUnit.SECONDS));
                var activation = pool.submit(() -> assertThrows(UserError.class, () -> f.jobs.activatePolicy(f.admin, Map.of(EDUCATION, 1))));
                commit.countDown(); approval.get(15, java.util.concurrent.TimeUnit.SECONDS); activation.get(15, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(f.initialPolicy, f.jobs.scoringPolicy(f.admin).version());
            } finally { commit.countDown(); }
        }
        f.jobs.close(f.company, first);
        long second = f.jobs.submit(f.company, 0, f.details());
        var saved = new java.util.concurrent.CountDownLatch(1); var activateCommit = new java.util.concurrent.CountDownLatch(1);
        JobRepository delayedPolicy = delayed(saved, activateCommit);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var activation = pool.submit(() -> new JobService(f.identity, delayedPolicy).activatePolicy(f.admin, Map.of(EDUCATION, 1)));
            try {
                assertTrue(saved.await(10, java.util.concurrent.TimeUnit.SECONDS));
                var approval = pool.submit(() -> assertThrows(UserError.class, () -> f.jobs.moderate(f.admin, second, true, "")));
                activateCommit.countDown(); var policy = activation.get(15, java.util.concurrent.TimeUnit.SECONDS); f.policies.add(policy.version());
                approval.get(15, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(Status.PENDING, f.jobs.open(f.company, second).status());
            } finally { activateCommit.countDown(); }
        }
    }

    private JobRepository delayed(java.util.concurrent.CountDownLatch written, java.util.concurrent.CountDownLatch commit) {
        return new JobRepository() {
            @Override public <T> T transaction(java.util.function.Function<Store, T> work) {
                return new JdbcJobs(f.database).transaction(store -> {
                    T result = work.apply(store); written.countDown();
                    try { if (!commit.await(15, java.util.concurrent.TimeUnit.SECONDS)) { throw new AssertionError("Policy race test timed out"); } }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                    return result;
                });
            }
        };
    }
}
