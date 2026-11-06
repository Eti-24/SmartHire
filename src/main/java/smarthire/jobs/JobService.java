package smarthire.jobs;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;
import smarthire.identity.IdentityService;
import smarthire.identity.Profile;
import smarthire.identity.Role;
import smarthire.identity.UserError;
import smarthire.skills.Skill;
import smarthire.skills.SkillVocabulary;
import smarthire.workspace.AdminActivity;
import static smarthire.jobs.Job.*;

public final class JobService {
    private final IdentityService identity;
    private final JobRepository repository;
    private final smarthire.documents.ManagedDocuments documents;

    public JobService(IdentityService identity, JobRepository repository) {
        this(identity, repository, null);
    }

    public JobService(IdentityService identity, JobRepository repository, smarthire.documents.ManagedDocuments documents) {
        this.identity = identity;
        this.repository = repository;
        this.documents = documents;
    }

    private <T> T access(UUID session, Role role, Function<JobRepository.Store, T> work) {
        Profile actor = identity.requireRole(session, role);
        return repository.transaction(store -> {
            if (!store.active(actor.id())) { throw new UserError("This account is disabled."); }
            return work.apply(store);
        });
    }

    public List<Company> companies(UUID session) {
        return access(session, Role.ADMIN, JobRepository.Store::companies);
    }

    public void verify(UUID session, long companyId, boolean verified) {
        Profile admin = identity.requireRole(session, Role.ADMIN);
        access(session, Role.ADMIN, store -> {
            if (company(store, companyId).verified() == verified) { return null; }
            store.verify(companyId, verified);
            store.audit(admin.id(), new AdminActivity.Change(AdminActivity.Action.COMPANY_VERIFICATION, "COMPANY",
                    Long.toString(companyId), verified ? "VERIFIED" : "UNVERIFIED"));
            return null;
        });
    }

    public List<Category> categories(UUID session) {
        Profile actor = identity.profile(session);
        return access(session, actor.role(), JobRepository.Store::categories);
    }

    public List<Skill> skills(UUID session) {
        Profile actor = identity.profile(session);
        return access(session, actor.role(), JobRepository.Store::skills);
    }

    public long saveCategory(UUID session, long id, String name) {
        String valid = text(name, "Category", 100);
        return access(session, Role.ADMIN, store -> {
            if (id != 0 && store.categories().stream().noneMatch(c -> c.id() == id)) {
                throw new UserError("Category no longer exists.");
            }
            if (store.categories().stream().anyMatch(c -> c.id() != id && c.name().equalsIgnoreCase(valid))) {
                throw new UserError("That category already exists.");
            }
            return store.saveCategory(id, valid);
        });
    }

    public void saveSkill(UUID session, Skill skill) {
        access(session, Role.ADMIN, store -> {
            List<Skill> catalog = new ArrayList<>(store.skills());
            catalog.removeIf(s -> s.id().equals(skill.id()));
            catalog.add(skill);
            try { new SkillVocabulary(catalog); }
            catch (IllegalArgumentException exception) { throw new UserError(exception.getMessage()); }
            store.saveSkill(skill);
            return null;
        });
    }

    public long submit(UUID session, long id, Details details) {
        Profile actor = identity.requireRole(session, Role.COMPANY);
        return access(session, Role.COMPANY, store -> {
            if (!company(store, actor.id()).verified()) { throw new UserError("An admin must verify your company before you submit jobs."); }
            if (id != 0) {
                Job previous = owned(store, id, actor.id());
                if (previous.status() == Status.CLOSED) { throw new UserError("Closed jobs cannot be edited. Create a new posting."); }
                if (store.hasApplications(id)) { throw new UserError("This job has applications. Its requirements are frozen; close it and create a revised posting."); }
            }
            return store.saveJob(actor.id(), id, validate(store, details));
        });
    }

    public void moderate(UUID session, long id, boolean approve, String reason) {
        Profile admin = identity.requireRole(session, Role.ADMIN);
        String decision = approve ? "Approved" : text(reason, "Rejection reason", 1000);
        access(session, Role.ADMIN, store -> {
            Job job = get(store, id);
            if (job.status() != Status.PENDING) { throw new UserError("Only pending jobs can be moderated. Refresh the list."); }
            Company company = company(store, job.companyId());
            if (approve && (!company.active() || !company.verified())) { throw new UserError("Only active, verified companies may publish jobs."); }
            if (approve) {
                validate(store, job.details());
                new smarthire.matching.MatchEngine().assess(smarthire.cv.CvProfile.Qualifications.empty(), job.details(),
                        new SkillVocabulary(store.skills()), store.activePolicy());
            }
            store.status(id, approve ? Status.APPROVED : Status.REJECTED, decision);
            store.audit(admin.id(), new AdminActivity.Change(AdminActivity.Action.JOB_MODERATION, "JOB",
                    Long.toString(id), approve ? "APPROVED" : "REJECTED"));
            return null;
        });
    }

    public void close(UUID session, long id) {
        Profile actor = identity.requireRole(session, Role.COMPANY);
        access(session, Role.COMPANY, store -> {
            owned(store, id, actor.id());
            store.status(id, Status.CLOSED, "Closed by company");
            return null;
        });
    }

    public List<Job> list(UUID session, String title, Long categoryId) {
        Profile actor = identity.profile(session);
        String query = title == null ? "" : title.strip().toLowerCase(Locale.ROOT);
        return access(session, actor.role(), store -> (actor.role() == Role.STUDENT ? store.unfilledJobs() : store.jobs()).stream()
                .filter(job -> switch (actor.role()) {
                    case ADMIN -> true;
                    case COMPANY -> job.companyId() == actor.id();
                    case STUDENT -> published(store, job);
                })
                .filter(job -> job.details().title().toLowerCase(Locale.ROOT).contains(query))
                .filter(job -> categoryId == null || job.details().categoryId() == categoryId)
                .toList());
    }

    public List<HiringRecord> hiringHistory(UUID session) {
        Profile actor = identity.profile(session);
        if (actor.role() == Role.ADMIN) { throw new UserError("Hiring history is available to students and companies."); }
        return access(session, actor.role(), store -> store.hiringHistory(actor.role() == Role.COMPANY ? actor.id() : null));
    }

    public List<CompanyHire> companyHires(UUID session) {
        Profile actor = identity.requireRole(session, Role.COMPANY);
        return access(session, Role.COMPANY, store -> store.companyHires(actor.id()));
    }

    public List<SavedJob> savedJobs(UUID session) {
        Profile actor = identity.requireRole(session, Role.STUDENT);
        return access(session, Role.STUDENT, store -> store.savedJobs(actor.id()));
    }

    public void saveBookmark(UUID session, long jobId) {
        Profile actor = identity.requireRole(session, Role.STUDENT);
        access(session, Role.STUDENT, store -> {
            Job job = get(store, jobId);
            if (!visible(store, job)) { throw new UserError("This posting is no longer available to save."); }
            store.saveBookmark(actor.id(), job);
            return null;
        });
    }

    public void removeBookmark(UUID session, long jobId) {
        Profile actor = identity.requireRole(session, Role.STUDENT);
        access(session, Role.STUDENT, store -> { store.removeBookmark(actor.id(), jobId); return null; });
    }

    public enum SavedStatus { AVAILABLE, CLOSED, FILLED, UNAVAILABLE }
    public record SavedJob(long jobId, String title, SavedStatus status, java.util.UUID applicationId) { }

    public Job open(UUID session, long id) {
        Profile actor = identity.profile(session);
        return access(session, actor.role(), store -> {
            Job job = get(store, id);
            if (actor.role() == Role.COMPANY && job.companyId() != actor.id()
                    || actor.role() == Role.STUDENT && !visible(store, job)) {
                throw new UserError("This posting is no longer available to your account.");
            }
            return job;
        });
    }

    public smarthire.matching.ScoringPolicy scoringPolicy(UUID session) {
        Profile actor = identity.profile(session);
        return access(session, actor.role(), JobRepository.Store::activePolicy);
    }

    public smarthire.matching.ScoringPolicy activatePolicy(UUID session, java.util.Map<smarthire.matching.ScoringPolicy.Category, Integer> weights) {
        return access(session, Role.ADMIN, store -> {
            var proposed = new smarthire.matching.ScoringPolicy("policy-" + UUID.randomUUID(), weights);
            var vocabulary = new SkillVocabulary(store.skills());
            var incompatible = new ArrayList<Long>();
            for (Job job : store.jobs()) {
                if (job.status() == Status.APPROVED || store.hasApplications(job.id())) {
                    try { new smarthire.matching.MatchEngine().assess(smarthire.cv.CvProfile.Qualifications.empty(), job.details(), vocabulary, proposed); }
                    catch (UserError e) { incompatible.add(job.id()); }
                }
            }
            if (!incompatible.isEmpty()) { throw new UserError("Policy cannot score these jobs: " + incompatible + ". The previous policy is unchanged."); }
            store.activatePolicy(proposed); return proposed;
        });
    }

    public java.util.Optional<smarthire.documents.StoredDocument> circular(UUID session, long jobId) {
        Profile actor = identity.profile(session);
        return access(session, actor.role(), store -> {
            readable(store, jobId, actor);
            var circular = store.circular(jobId);
            circular.ifPresent(doc -> documentStore().read(doc.id()));
            return circular;
        });
    }

    public byte[] downloadCircular(UUID session, long jobId) {
        Profile actor = identity.profile(session);
        return access(session, actor.role(), store -> {
            readable(store, jobId, actor);
            var doc = store.circular(jobId).orElseThrow(() -> new UserError("This posting has no circular."));
            return documentStore().read(doc.id());
        });
    }

    public void attachCircular(UUID session, long jobId, java.nio.file.Path source) {
        Profile actor = identity.requireRole(session, Role.COMPANY);
        open(session, jobId);
        try (var pending = documentStore().prepare(source)) {
            access(session, Role.COMPANY, store -> {
                Job job = owned(store, jobId, actor.id());
                if (job.status() == Status.CLOSED || store.hasApplications(jobId)) { throw new UserError("Circular changes are frozen for closed jobs or jobs with applications."); }
                if (!company(store, actor.id()).verified()) { throw new UserError("Your company must be verified before attaching a circular."); }
                pending.validate(); store.attach(jobId, pending.document());
                store.status(jobId, Status.PENDING, "Circular changed; awaiting approval"); return null;
            });
        }
    }

    public RequirementSuggestions suggest(UUID session, String text) {
        return access(session, Role.COMPANY, store -> new RequirementExtractor().suggest(text, new SkillVocabulary(store.skills())));
    }

    private Job readable(JobRepository.Store store, long id, Profile actor) {
        Job job = get(store, id);
        if (actor.role() == Role.COMPANY && job.companyId() != actor.id() || actor.role() == Role.STUDENT && !visible(store, job)) {
            throw new UserError("This posting is no longer available to your account.");
        }
        return job;
    }

    private smarthire.documents.ManagedDocuments documentStore() {
        if (documents == null) { throw new UserError("Document storage is unavailable. Restart SmartHire."); }
        return documents;
    }

    private static boolean visible(JobRepository.Store store, Job job) {
        return published(store, job) && job.remainingVacancies() > 0;
    }

    private static boolean published(JobRepository.Store store, Job job) {
        Company company = company(store, job.companyId());
        return job.status() == Status.APPROVED && company.active() && company.verified();
    }

    private static Company company(JobRepository.Store store, long id) {
        return store.companies().stream().filter(c -> c.id() == id).findFirst()
                .orElseThrow(() -> new UserError("Company not found."));
    }

    private static Job get(JobRepository.Store store, long id) {
        return store.lockJob(id).orElseThrow(() -> new UserError("Job not found."));
    }

    private static Job owned(JobRepository.Store store, long id, long owner) {
        Job job = get(store, id);
        if (job.companyId() != owner) { throw new UserError("You can only change your own jobs."); }
        return job;
    }

    private static Details validate(JobRepository.Store store, Details details) {
        if (details == null) { throw new UserError("Job details are required."); }
        if (details.vacancyCount() < 1) { throw new UserError("Enter at least one vacancy."); }
        String title = text(details.title(), "Title", 160);
        String description = text(details.description(), "Description", 10000);
        String location = text(details.location(), "Location", 180);
        if (store.categories().stream().noneMatch(c -> c.id() == details.categoryId())) { throw new UserError("Choose an existing category."); }
        BigDecimal min = details.salaryMin(), max = details.salaryMax();
        if (min == null || max == null || min.signum() < 0 || min.compareTo(max) > 0
                || max.compareTo(new BigDecimal("9999999999.99")) > 0
                || min.stripTrailingZeros().scale() > 2 || max.stripTrailingZeros().scale() > 2) {
            throw new UserError("Enter a nonnegative salary range with at most two decimal places.");
        }
        var vocabulary = new SkillVocabulary(store.skills());
        var seen = new HashSet<String>();
        var requirements = new ArrayList<Requirement>();
        boolean scored = false, experience = false, tag = false, degree = false, field = false;
        for (Requirement r : details.requirements()) {
            if (r == null || r.kind() == null || r.priority() == null) { throw new UserError("Complete every requirement row."); }
            String value = r.value() == null ? "" : r.value().strip();
            if (r.months() < 0 || r.months() > 1200 || r.kind() != Kind.EXPERIENCE && r.months() != 0) {
                throw new UserError("Only experience requirements have months (0–1200).");
            }
            switch (r.kind()) {
                case SKILL, PROJECT, EXPERIENCE_TAG -> {
                    String entered = value;
                    value = vocabulary.findById(entered).or(() -> vocabulary.resolve(entered)).map(Skill::id)
                            .orElseThrow(() -> new UserError("Choose a known skill for skills, projects and experience tags."));
                    tag |= r.kind() == Kind.EXPERIENCE_TAG;
                    scored |= r.kind() != Kind.EXPERIENCE_TAG;
                }
                case DEGREE -> {
                    try { Degree.valueOf(value); } catch (IllegalArgumentException e) { throw new UserError("Choose a supported minimum degree."); }
                    degree = true; scored = true;
                }
                case FIELD -> { value = canonical(text(value, "Degree field", 180)); field = true; }
                case EXPERIENCE -> { value = ""; experience = r.months() > 0; scored |= experience; }
                case CERTIFICATION -> { value = canonical(text(value, "Certification", 180)); scored = true; }
            }
            String key = r.kind() + ":" + (r.kind() == Kind.DEGREE || r.kind() == Kind.FIELD || r.kind() == Kind.EXPERIENCE ? "" : value);
            if (!seen.add(key)) { throw new UserError("Duplicate requirements are not allowed."); }
            if (r.kind() == Kind.EXPERIENCE_TAG && r.mandatory()) { throw new UserError("Mark experience as mandatory, not its relevance tags."); }
            if (r.kind() == Kind.EXPERIENCE && r.months() == 0 && r.mandatory()) { throw new UserError("Mandatory experience must exceed zero months."); }
            requirements.add(new Requirement(r.kind(), value, r.months(), r.priority(), r.mandatory()));
        }
        if (!scored) { throw new UserError("Add at least one scored requirement."); }
        if (experience != tag) { throw new UserError("Positive experience requires at least one relevance skill tag, and tags require positive experience."); }
        if (field && !degree) { throw new UserError("A degree field requires a minimum degree."); }
        return new Details(title, description, details.categoryId(), location, min, max, requirements, details.vacancyCount());
    }

    private static String canonical(String value) {
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("(?U)\\s+", " ").strip();
    }

    private static String text(String value, String label, int maximum) {
        if (value == null || value.isBlank() || value.strip().length() > maximum) {
            throw new UserError(label + " is required and must be at most " + maximum + " characters.");
        }
        return value.strip();
    }
}
