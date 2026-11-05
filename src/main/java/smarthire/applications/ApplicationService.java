package smarthire.applications;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import smarthire.cv.CvProfile;
import smarthire.documents.ManagedDocuments;
import smarthire.identity.*;
import smarthire.jobs.Job;
import smarthire.matching.*;

/** Submission and transitions recheck permissions inside the same transaction as writes. */
public final class ApplicationService {
    private final IdentityService identity;
    private final ApplicationRepository repository;
    private final ManagedDocuments documents;
    public ApplicationService(IdentityService identity, ApplicationRepository repository, ManagedDocuments documents) {
        this.identity = identity; this.repository = repository; this.documents = documents;
    }

    public Application apply(UUID session, long jobId) {
        Profile actor = identity.requireRole(session, Role.STUDENT);
        return repository.transaction(store -> {
            Job job = job(store, jobId);
            Profile student = store.activeActor(actor.id(), Role.STUDENT);
            var company = store.jobs().companies().stream().filter(c -> c.id() == job.companyId()).findFirst().orElseThrow();
            if (job.status() != Job.Status.APPROVED || !company.active() || !company.verified()) { throw new UserError("This job is no longer open for applications."); }
            if (store.list(student.id(), Role.STUDENT).stream().anyMatch(a -> a.jobId() == jobId)) { throw new UserError("You have already applied to this job."); }
            CvProfile cv = store.cvs(student.id()).current().orElseThrow(() -> new UserError("Confirm a CV review before applying."));
            var approval = store.cvs(student.id()).approval(cv.id());
            if (approval.status() != smarthire.cv.CvApproval.Status.APPROVED) {
                throw new UserError("Your current CV must be approved by an admin before applying. " + approval.message());
            }
            documents.read(cv.documentId());
            MatchResult match = new MatchEngine().assess(cv.qualifications(), job.details(), store.cvs(student.id()).vocabulary(), store.jobs().activePolicy());
            if (!match.eligible()) { throw new UserError("You do not meet the mandatory requirements: " + String.join("; ", match.failedMandatory())); }
            Application application = new Application(UUID.randomUUID(), student.id(), jobId, cv.id(), Instant.now(), Application.Status.APPLIED,
                    student, match.policyVersion(), match.percentage(), explanation(match));
            store.insert(application); return application;
        });
    }

    public List<Entry> list(UUID session, Long jobFilter, Application.Status statusFilter) {
        Profile actor = participant(session);
        return repository.transaction(store -> {
            store.activeActor(actor.id(), actor.role());
            return store.list(actor.id(), actor.role()).stream()
                    .filter(a -> jobFilter == null || a.jobId() == jobFilter)
                    .filter(a -> statusFilter == null || a.status() == statusFilter)
                    .map(a -> entry(store, a))
                    .sorted(Comparator.comparingLong((Entry e) -> e.job().id())
                            .thenComparing(Comparator.comparingDouble((Entry e) -> e.ranking().percentage()).reversed())
                            .thenComparing(e -> e.application().submittedAt()).thenComparing(e -> e.application().id()))
                    .toList();
        });
    }

    public Entry open(UUID session, UUID id) {
        Profile actor = participant(session);
        return repository.transaction(store -> { store.activeActor(actor.id(), actor.role()); return entry(store, owned(store, id, actor)); });
    }

    public byte[] download(UUID session, UUID id) {
        Profile actor = participant(session);
        return repository.transaction(store -> {
            store.activeActor(actor.id(), actor.role());
            Application a = owned(store, id, actor);
            return documents.read(store.cvs(a.studentId()).find(a.cvVersion()).orElseThrow().documentId());
        });
    }

    public void transition(UUID session, UUID id, Application.Status target) {
        Profile actor = participant(session);
        repository.transaction(store -> {
            store.activeActor(actor.id(), actor.role());
            Application a = owned(store, id, actor);
            boolean allowed = actor.role() == Role.STUDENT
                    ? target == Application.Status.WITHDRAWN && a.status().undecided()
                    : a.status() == Application.Status.APPLIED && (target == Application.Status.SHORTLISTED || target == Application.Status.REJECTED)
                        || a.status() == Application.Status.SHORTLISTED && (target == Application.Status.HIRED || target == Application.Status.REJECTED);
            if (!allowed) { throw new UserError("That status change is not allowed. Refresh the application."); }
            store.status(id, target); return null;
        });
    }

    private Profile participant(UUID session) {
        Profile actor = identity.profile(session);
        if (actor.role() == Role.ADMIN) { throw new UserError("Application review is available to the student and owning company."); }
        return actor;
    }
    private static Job job(ApplicationRepository.Store store, long id) { return store.jobs().lockJob(id).orElseThrow(() -> new UserError("Job not found.")); }
    private static Application owned(ApplicationRepository.Store store, UUID id, Profile actor) {
        Application a = store.lock(id).orElseThrow(() -> new UserError("Application not found."));
        Job job = job(store, a.jobId());
        if (actor.role() == Role.STUDENT ? a.studentId() != actor.id() : job.companyId() != actor.id()) { throw new UserError("This application does not belong to your account."); }
        return a;
    }
    private static Entry entry(ApplicationRepository.Store store, Application a) {
        Job job = job(store, a.jobId());
        CvProfile cv = store.cvs(a.studentId()).find(a.cvVersion()).orElseThrow(() -> new UserError("Submitted CV history is unavailable."));
        MatchResult ranking = new MatchEngine().assess(cv.qualifications(), job.details(), store.cvs(a.studentId()).vocabulary(), store.jobs().activePolicy());
        return new Entry(a, job, cv, ranking);
    }
    public record Entry(Application application, Job job, CvProfile cv, MatchResult ranking) { }

    public static String explanation(MatchResult match) {
        StringBuilder text = new StringBuilder(String.format(Locale.ROOT, "Match: %.2f%% | %s | Policy: %s%n", match.percentage(), match.eligible() ? "Eligible" : "Not eligible", match.policyVersion()));
        for (var c : match.contributions()) {
            text.append(c.category()).append(": fraction=").append(c.fraction()).append(", weight=").append(c.normalizedWeight())
                    .append(", points=").append(c.percentagePoints()).append('\n');
            c.matched().forEach(item -> text.append("  Matched: ").append(item).append('\n'));
            c.missing().forEach(item -> text.append("  Missing/partial: ").append(item).append('\n'));
        }
        text.append("Relevant months: ").append(match.relevantMonths()).append(" | tags: ").append(String.join(", ", match.relevanceTags())).append('\n');
        match.mandatoryChecks().forEach(c -> text.append(c.satisfied() ? "Met: " : "FAILED: ").append(c.requirement()).append('\n'));
        match.suggestions().forEach(s -> text.append("Suggestion: ").append(s).append('\n'));
        return text.toString();
    }
}
