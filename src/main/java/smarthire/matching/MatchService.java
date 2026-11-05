package smarthire.matching;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import smarthire.cv.CvProfile;
import smarthire.cv.CvService;
import smarthire.identity.IdentityService;
import smarthire.identity.Role;
import smarthire.identity.UserError;
import smarthire.jobs.Job;
import smarthire.jobs.JobService;
import smarthire.skills.SkillVocabulary;

/** Recalculates on each request; no cached results can outlive CV or job changes. */
public final class MatchService {
    private final IdentityService identity;
    private final CvService cvs;
    private final JobService jobs;
    private final MatchEngine engine = new MatchEngine();

    public MatchService(IdentityService identity, CvService cvs, JobService jobs) {
        this.identity = identity; this.cvs = cvs; this.jobs = jobs;
    }

    public Recommendations recommend(UUID session) {
        identity.requireRole(session, Role.STUDENT);
        Optional<CvProfile> current = cvs.current(session);
        if (current.isEmpty()) { return new Recommendations(null, List.of()); }
        var vocabulary = new SkillVocabulary(jobs.skills(session));
        var policy = jobs.scoringPolicy(session);
        CvProfile cv = current.orElseThrow();
        List<Recommendation> results = jobs.list(session, "", null).stream()
                .map(job -> assess(cv, job, vocabulary, policy))
                .sorted(Comparator.comparing((Recommendation r) -> !r.match().eligible())
                        .thenComparing(Comparator.comparingDouble((Recommendation r) -> r.match().percentage()).reversed())
                        .thenComparingLong(r -> r.job().id())).toList();
        identity.requireRole(session, Role.STUDENT);
        return new Recommendations(cv.id(), results);
    }

    /** Opening a displayed recommendation rechecks both visibility and the latest reviewed CV. */
    public Recommendation open(UUID session, long jobId) {
        identity.requireRole(session, Role.STUDENT);
        Job job = jobs.open(session, jobId);
        CvProfile cv = cvs.current(session).orElseThrow(() -> new UserError("Confirm a CV review in My CV before requesting matches."));
        Recommendation result = assess(cv, job, new SkillVocabulary(jobs.skills(session)), jobs.scoringPolicy(session));
        identity.requireRole(session, Role.STUDENT);
        return result;
    }

    private Recommendation assess(CvProfile cv, Job job, SkillVocabulary vocabulary, ScoringPolicy policy) {
        return new Recommendation(job, cv.id(), engine.assess(cv.qualifications(), job.details(), vocabulary, policy));
    }

    public Comparison compare(UUID session, long firstJob, long secondJob) {
        identity.requireRole(session, Role.STUDENT);
        if (firstJob == secondJob) { throw new UserError("Select two different jobs to compare."); }
        Job first = jobs.open(session, firstJob), second = jobs.open(session, secondJob);
        CvProfile cv = cvs.current(session).orElseThrow(() -> new UserError("Confirm a CV review before comparing matches."));
        var vocabulary = new SkillVocabulary(jobs.skills(session));
        var policy = jobs.scoringPolicy(session);
        var similarity = new TextSimilarity().compare(cv.extractedText(), List.of(first.details().description(), second.details().description()));
        return new Comparison(assess(cv, first, vocabulary, policy), assess(cv, second, vocabulary, policy), similarity.get(0), similarity.get(1));
    }

    public record Comparison(Recommendation first, Recommendation second, double firstTextSimilarity, double secondTextSimilarity) { }

    public record Recommendation(Job job, UUID cvVersion, MatchResult match) { }
    public record Recommendations(UUID cvVersion, List<Recommendation> jobs) {
        public Recommendations { jobs = List.copyOf(jobs); }
        public boolean needsReview() { return cvVersion == null; }
    }
}
