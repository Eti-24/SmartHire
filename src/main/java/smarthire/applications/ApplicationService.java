package smarthire.applications;

import java.time.Instant;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
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
    private final Clock clock;
    public ApplicationService(IdentityService identity, ApplicationRepository repository, ManagedDocuments documents) {
        this(identity, repository, documents, Clock.systemUTC());
    }

    public ApplicationService(IdentityService identity, ApplicationRepository repository, ManagedDocuments documents, Clock clock) {
        this.identity = identity; this.repository = repository; this.documents = documents;
        this.clock = clock;
    }

    public Application apply(UUID session, long jobId) {
        Profile actor = identity.requireRole(session, Role.STUDENT);
        return repository.transaction(store -> {
            Job job = job(store, jobId);
            Profile student = store.activeActor(actor.id(), Role.STUDENT);
            if (job.remainingVacancies() == 0) { throw new UserError("All vacancies for this posting have been filled."); }
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
            Job posting = job(store, a.jobId());
            if (target == Application.Status.HIRED && posting.remainingVacancies() == 0) {
                throw new UserError("All vacancies for this posting have already been filled.");
            }
            store.status(id, target);
            if (target == Application.Status.HIRED || target == Application.Status.REJECTED || target == Application.Status.WITHDRAWN) {
                cancelInterview(store, a, posting, clock.instant(), "Application " + target.name().toLowerCase(Locale.ROOT));
            }
            if (target == Application.Status.HIRED && posting.remainingVacancies() == 1) {
                Instant now = clock.instant();
                for (Interview other : store.currentInterviews(null, null, a.jobId())) {
                    if (!other.applicationId().equals(id) && other.canCancel(now)) {
                        Application applicant = store.lock(other.applicationId()).orElseThrow();
                        cancelInterview(store, applicant, posting, now, "Posting filled");
                    }
                }
            }
            return null;
        });
    }

    public record InterviewEntry(UUID applicationId, String job, String participant, boolean schedulingOpen, List<Interview> history) {
        public Interview current() { return history.isEmpty() ? null : history.getFirst(); }
    }

    public InterviewEntry interview(UUID session, UUID id) {
        Profile actor = participant(session);
        return repository.transaction(store -> {
            store.activeActor(actor.id(), actor.role());
            Application a = owned(store, id, actor);
            return interviewEntry(store, actor, a);
        });
    }

    public List<InterviewEntry> interviews(UUID session) {
        Profile actor = participant(session);
        return repository.transaction(store -> {
            store.activeActor(actor.id(), actor.role());
            boolean student = actor.role() == Role.STUDENT;
            return store.currentInterviews(student ? actor.id() : null, student ? null : actor.id(), null).stream()
                    .map(i -> interviewEntry(store, actor, store.lock(i.applicationId()).orElseThrow())).toList();
        });
    }

    private static InterviewEntry interviewEntry(ApplicationRepository.Store store, Profile actor, Application a) {
        Job job = job(store, a.jobId());
        boolean otherActive = store.jobs().active(actor.role() == Role.STUDENT ? job.companyId() : a.studentId());
        return new InterviewEntry(a.id(), job.details().title(), actor.role() == Role.STUDENT ? job.companyName() : a.submittedProfile().name(),
                otherActive && a.status() == Application.Status.SHORTLISTED && job.remainingVacancies() > 0,
                store.interviewHistory(a.id()));
    }

    public Interview changeInterview(UUID session, UUID id, int revision, Interview.Action action, Interview.Slot proposed) {
        Profile actor = participant(session);
        boolean company = actor.role() == Role.COMPANY;
        if (action == null || company != (action == Interview.Action.PROPOSE || action == Interview.Action.CANCEL)) {
            throw new UserError("This interview action is not available to your role.");
        }
        return repository.transaction(store -> {
            store.activeActor(actor.id(), actor.role());
            Application a = owned(store, id, actor);
            Job job = job(store, a.jobId());
            store.activeActor(company ? a.studentId() : job.companyId(), company ? Role.STUDENT : Role.COMPANY);
            if (a.status() != Application.Status.SHORTLISTED || job.remainingVacancies() == 0) {
                throw new UserError("Interviews require a shortlisted application and a remaining vacancy.");
            }
            Interview current = store.interviewHistory(id).stream().findFirst().orElse(null);
            if ((current == null ? 0 : current.revision()) != revision) {
                throw new UserError("This interview changed. Refresh before responding.");
            }
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            if (action == Interview.Action.PROPOSE && current != null && current.inProgress(now)) {
                throw new UserError("Wait for the confirmed interview to end before proposing another time.");
            }
            Interview.Slot slot = action == Interview.Action.PROPOSE ? validSlot(proposed, now) : requireCurrentSlot(current, action, now);
            Interview.Status status = switch (action) {
                case PROPOSE -> Interview.Status.PROPOSED;
                case ACCEPT -> Interview.Status.CONFIRMED;
                case DECLINE -> Interview.Status.DECLINED;
                case RESCHEDULE -> Interview.Status.RESCHEDULE_REQUESTED;
                case CANCEL -> Interview.Status.CANCELLED;
            };
            if (action == Interview.Action.ACCEPT && (conflict(store.currentInterviews(a.studentId(), null, null), id, slot)
                    || conflict(store.currentInterviews(null, job.companyId(), null), id, slot))) {
                throw new UserError("This time overlaps another confirmed interview. Request another time.");
            }
            Interview event = new Interview(id, revision + 1, status, slot, now, actor.role().name(), "");
            store.addInterview(event, a.studentId(), job.companyId());
            return event;
        });
    }

    private static Interview.Slot requireCurrentSlot(Interview current, Interview.Action action, Instant now) {
        boolean allowed = current != null && switch (action) {
            case ACCEPT, DECLINE -> current.status() == Interview.Status.PROPOSED && current.slot().start().isAfter(now);
            case RESCHEDULE -> (current.status() == Interview.Status.PROPOSED || current.status() == Interview.Status.CONFIRMED)
                    && current.slot().start().isAfter(now);
            case CANCEL -> current.canCancel(now);
            case PROPOSE -> false;
        };
        if (!allowed) { throw new UserError("That interview action is no longer available. Refresh the interview."); }
        return current.slot();
    }

    private static Interview.Slot validSlot(Interview.Slot proposed, Instant now) {
        if (proposed == null || proposed.start() == null || proposed.end() == null || proposed.zone() == null) {
            throw new UserError("Enter a start time, end time and time zone.");
        }
        Instant start = proposed.start().truncatedTo(ChronoUnit.MICROS), end = proposed.end().truncatedTo(ChronoUnit.MICROS);
        if (!start.isAfter(now) || !end.isAfter(start) || end.isAfter(now.plus(3650, ChronoUnit.DAYS))) {
            throw new UserError("Choose a future interview with an end after its start and within ten years.");
        }
        String location = proposed.location() == null ? "" : proposed.location().strip();
        if (location.isEmpty() || location.length() > 500) { throw new UserError("Enter a location or meeting link of up to 500 characters."); }
        return new Interview.Slot(start, end, proposed.zone(), location);
    }

    public static Instant interviewTime(LocalDateTime time, ZoneId zone) {
        var offsets = zone.getRules().getValidOffsets(time);
        if (offsets.size() != 1) { throw new UserError("That local time is skipped or ambiguous in the selected zone. Choose another time."); }
        return time.toInstant(offsets.getFirst());
    }

    private static boolean conflict(List<Interview> entries, UUID id, Interview.Slot slot) {
        return entries.stream().anyMatch(entry -> !entry.applicationId().equals(id)
                && entry.status() == Interview.Status.CONFIRMED && entry.slot().overlaps(slot));
    }

    private static void cancelInterview(ApplicationRepository.Store store, Application a, Job job, Instant now, String reason) {
        store.interviewHistory(a.id()).stream().findFirst().filter(current -> current.canCancel(now)).ifPresent(current ->
                store.addInterview(new Interview(a.id(), current.revision() + 1, Interview.Status.CANCELLED, current.slot(), now,
                        "SYSTEM", reason), a.studentId(), job.companyId()));
    }

    public RecruiterFeedback.Review feedback(UUID session, UUID applicationId) {
        Profile actor = identity.requireRole(session, Role.COMPANY);
        return repository.transaction(store -> {
            store.activeActor(actor.id(), Role.COMPANY);
            return feedbackReview(store, owned(store, applicationId, actor));
        });
    }

    public List<RecruiterFeedback.Candidate> feedbackCandidates(UUID session, UUID applicationId) {
        Profile actor = identity.requireRole(session, Role.COMPANY);
        return repository.transaction(store -> {
            store.activeActor(actor.id(), Role.COMPANY);
            Application selected = owned(store, applicationId, actor);
            return store.list(actor.id(), Role.COMPANY).stream()
                    .filter(a -> a.jobId() == selected.jobId() && !a.id().equals(applicationId))
                    .map(a -> new RecruiterFeedback.Candidate(a.id(), a.submittedProfile().name())).toList();
        });
    }

    public List<RecruiterFeedback.Review> compareFeedback(UUID session, UUID first, UUID second) {
        Profile actor = identity.requireRole(session, Role.COMPANY);
        return repository.transaction(store -> {
            store.activeActor(actor.id(), Role.COMPANY);
            Application a = owned(store, first, actor), b = owned(store, second, actor);
            if (first.equals(second) || a.jobId() != b.jobId()) { throw new UserError("Choose two applicants for the same posting."); }
            return List.of(feedbackReview(store, a), feedbackReview(store, b));
        });
    }

    public RecruiterFeedback.Review saveFeedback(UUID session, UUID id, int revision, String notes, RecruiterFeedback.Scorecard scorecard) {
        Profile actor = identity.requireRole(session, Role.COMPANY);
        return repository.transaction(store -> {
            store.activeActor(actor.id(), Role.COMPANY);
            Application a = owned(store, id, actor);
            var previous = feedbackReview(store, a);
            if (previous.feedback().revision() != revision) { throw new UserError("Feedback changed. Refresh before saving."); }
            String cleanNotes = feedbackText(notes);
            RecruiterFeedback.Scorecard cleanScore = null;
            if (scorecard != null) {
                var old = previous.feedback().scorecard();
                int occurrence = scorecard.interviewRevision();
                if ((old == null || old.interviewRevision() != occurrence)
                        && !java.util.Objects.equals(previous.completedInterviewRevision(), occurrence)) {
                    throw new UserError("A scorecard requires an elapsed confirmed interview.");
                }
                cleanScore = new RecruiterFeedback.Scorecard(occurrence, rating(scorecard.technical()), rating(scorecard.communication()),
                        rating(scorecard.roleFit()), feedbackText(scorecard.comments()));
            }
            var updated = new RecruiterFeedback(revision + 1, cleanNotes, cleanScore, clock.instant().truncatedTo(ChronoUnit.MICROS));
            store.saveFeedback(id, updated);
            return new RecruiterFeedback.Review(id, a.submittedProfile().name(), updated, previous.completedInterviewRevision());
        });
    }

    private RecruiterFeedback.Review feedbackReview(ApplicationRepository.Store store, Application a) {
        var history = store.interviewHistory(a.id());
        Instant endedBy = clock.instant();
        Integer completed = null;
        for (var event : history) {
            if (event.status() == Interview.Status.CONFIRMED && !event.slot().end().isAfter(endedBy)) {
                completed = event.revision();
                break;
            }
            endedBy = event.changedAt();
        }
        return new RecruiterFeedback.Review(a.id(), a.submittedProfile().name(), store.feedback(a.id()), completed);
    }

    private static Integer rating(Integer rating) {
        if (rating != null && (rating < 1 || rating > 5)) { throw new UserError("Ratings must be between 1 and 5."); }
        return rating;
    }

    private static String feedbackText(String text) {
        String clean = text == null ? "" : text.strip();
        if (clean.length() > 4000) { throw new UserError("Notes and feedback each allow up to 4000 characters."); }
        return clean;
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
