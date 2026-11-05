package smarthire.cv;

import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import smarthire.documents.ManagedDocuments;
import smarthire.documents.StoredDocument;
import smarthire.identity.IdentityService;
import smarthire.identity.Role;
import smarthire.identity.UserError;
import smarthire.skills.Skill;
import smarthire.skills.SkillVocabulary;
import static smarthire.cv.CvProfile.*;

/** All entry points are intended for background work, including cancelling a draft. */
public final class CvService {
    private final IdentityService identity;
    private final CvRepository repository;
    private final ManagedDocuments documents;
    private final Clock clock;

    public CvService(IdentityService identity, CvRepository repository, ManagedDocuments documents) {
        this(identity, repository, documents, Clock.systemDefaultZone());
    }

    public CvService(IdentityService identity, CvRepository repository, ManagedDocuments documents, Clock clock) {
        this.identity = identity; this.repository = repository; this.documents = documents; this.clock = clock;
    }

    public Optional<CvProfile> current(UUID session) {
        return repository.transaction(student(session), CvRepository.Store::current);
    }

    public List<Version> history(UUID session) {
        return repository.transaction(student(session), CvRepository.Store::history);
    }

    public CvApproval approval(UUID session, UUID version) {
        return repository.transaction(student(session), store -> store.approval(version));
    }

    public java.util.Map<UUID, CvApproval> approvals(UUID session) {
        return repository.transaction(student(session), store -> {
            var result = new java.util.HashMap<UUID, CvApproval>();
            for (var version : store.history()) { result.put(version.id(), store.approval(version.id())); }
            return java.util.Map.copyOf(result);
        });
    }

    public CvProfile review(UUID session, UUID version) {
        return repository.transaction(student(session), store -> store.find(version)
                .orElseThrow(() -> new UserError("This CV version is not available to your account.")));
    }

    public List<Skill> skills(UUID session) {
        return repository.transaction(student(session), store -> store.vocabulary().skills());
    }

    public Draft upload(UUID session, Path source) {
        long owner = student(session);
        UUID base = repository.transaction(owner, store -> store.current().map(CvProfile::id).orElse(null));
        var pending = documents.prepare(source);
        try {
            student(session);
            return repository.transaction(owner, store -> new Draft(owner, base, pending.document(), pending,
                    new CvExtractor().suggest(pending.document().text(), store.vocabulary(), YearMonth.now(clock))));
        } catch (RuntimeException exception) { pending.close(); throw exception; }
    }

    public Draft edit(UUID session) {
        long owner = student(session);
        return repository.transaction(owner, store -> {
            CvProfile current = store.current().orElseThrow(() -> new UserError("Upload a CV before reviewing qualifications."));
            documents.read(current.documentId());
            return new Draft(owner, current.id(), current.documentId(), current.originalName(), current.extractedText(), null, current.qualifications());
        });
    }

    public CvProfile confirm(UUID session, Draft draft, Qualifications input) {
        long owner = student(session);
        if (draft == null || draft.service() != this || draft.owner != owner) { throw new UserError("This CV review belongs to another account."); }
        synchronized (draft) {
            draft.check();
            CvProfile saved = repository.transaction(owner, store -> {
                UUID current = store.current().map(CvProfile::id).orElse(null);
                if (!Objects.equals(current, draft.base)) { throw new UserError("Your current CV changed. Cancel this review and reopen it."); }
                Qualifications valid = validate(input, store.vocabulary());
                if (draft.pending != null) { draft.pending.validate(); }
                else { documents.read(draft.documentId); }
                CvProfile profile = new CvProfile(UUID.randomUUID(), owner, draft.documentId, draft.originalName,
                        draft.extractedText, Instant.now(clock), valid);
                store.save(profile, draft.pending == null ? null : draft.pending.document());
                return profile;
            });
            draft.close();
            return saved;
        }
    }

    public byte[] download(UUID session, UUID version) {
        return repository.transaction(student(session), store -> documents.read(store.find(version)
                .orElseThrow(() -> new UserError("This CV version is not available to your account.")).documentId()));
    }

    private long student(UUID session) { return identity.requireRole(session, Role.STUDENT).id(); }

    private Qualifications validate(Qualifications input, SkillVocabulary vocabulary) {
        if (input == null) { throw new UserError("Review the qualifications before saving."); }
        if (input.projects().size() > 100 || input.experience().size() > 100 || input.certifications().size() > 100) {
            throw new UserError("Use at most 100 projects, work entries, and certifications each.");
        }
        String field = canonical(text(input.field(), "Degree field", 180, false));
        if (input.degree() == null && !field.isEmpty()) { throw new UserError("Choose a degree level or clear the degree field."); }
        var projects = new ArrayList<Project>();
        for (Project project : input.projects()) {
            projects.add(new Project(text(project.evidence(), "Project evidence", 2000, true), canonicalSkills(project.skills(), vocabulary)));
        }
        var experience = new ArrayList<Experience>();
        YearMonth now = YearMonth.now(clock);
        for (Experience entry : input.experience()) {
            YearMonth end = entry.ongoing() ? now : entry.end();
            if (entry.start() == null || end == null || entry.start().getYear() < 1900 || entry.start().isAfter(end)
                    || end.isAfter(now) || entry.end() != null && entry.end().isAfter(now)) {
                throw new UserError("Work entries need valid YYYY-MM dates, in order, with no future months.");
            }
            experience.add(new Experience(text(entry.evidence(), "Work evidence", 2000, true), entry.start(), end,
                    entry.ongoing(), canonicalSkills(entry.skills(), vocabulary)));
        }
        var certifications = new ArrayList<String>();
        var seen = new HashSet<String>();
        for (String value : input.certifications()) {
            String valid = canonical(text(value, "Certification", 180, true));
            if (!seen.add(valid)) { throw new UserError("Duplicate certifications are not allowed."); }
            certifications.add(valid);
        }
        return new Qualifications(canonicalSkills(input.skills(), vocabulary), input.degree(), field, projects, experience, certifications);
    }

    private static List<String> canonicalSkills(List<String> values, SkillVocabulary vocabulary) {
        var result = new ArrayList<String>();
        var seen = new HashSet<String>();
        for (String value : values) {
            String entered = value.strip();
            String id = vocabulary.findById(entered).or(() -> vocabulary.resolve(entered)).map(Skill::id)
                    .orElseThrow(() -> new UserError("Unknown skill: " + entered + ". Choose a skill from the catalog."));
            if (!seen.add(id)) { throw new UserError("A skill appears more than once in the same list: " + entered); }
            result.add(id);
        }
        return List.copyOf(result);
    }

    private static String canonical(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("(?U)\\s+", " ").strip();
    }

    private static String text(String value, String label, int maximum, boolean required) {
        String valid = value == null ? "" : value.strip();
        if (required && valid.isEmpty() || valid.length() > maximum) { throw new UserError(label + " must " + (required ? "be present and " : "") + "contain at most " + maximum + " characters."); }
        return valid;
    }

    public final class Draft implements AutoCloseable {
        private final long owner;
        private final UUID base;
        private final UUID documentId;
        private final String originalName;
        private final String extractedText;
        private final ManagedDocuments.Pending pending;
        private final Qualifications suggestions;
        private boolean closed;
        private Draft(long owner, UUID base, StoredDocument document, ManagedDocuments.Pending pending, Qualifications suggestions) {
            this(owner, base, document.id(), document.originalName(), document.text(), pending, suggestions);
        }
        private Draft(long owner, UUID base, UUID documentId, String originalName, String extractedText, ManagedDocuments.Pending pending, Qualifications suggestions) {
            this.owner = owner; this.base = base; this.documentId = documentId; this.originalName = originalName;
            this.extractedText = extractedText; this.pending = pending; this.suggestions = suggestions;
        }
        private CvService service() { return CvService.this; }
        public String originalName() { return originalName; }
        public String extractedText() { return extractedText; }
        public Qualifications suggestions() { return suggestions; }
        private void check() { if (closed) { throw new UserError("This review is closed. Upload or reopen your CV."); } }
        @Override public synchronized void close() {
            if (closed) { return; }
            if (pending != null) { pending.close(); }
            closed = true;
        }
    }
}
