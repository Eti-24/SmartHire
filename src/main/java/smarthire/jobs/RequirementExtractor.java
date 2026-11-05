package smarthire.jobs;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import smarthire.identity.UserError;
import smarthire.skills.SkillVocabulary;
import static smarthire.jobs.Job.*;

/** Suggestions are optional and nonmandatory until the recruiter explicitly edits and submits them. */
public final class RequirementExtractor {
    public RequirementSuggestions suggest(String text, SkillVocabulary vocabulary) {
        if (text == null || text.isBlank() || text.length() > 500_000) { throw new UserError("Provide readable requirement text of at most 500,000 characters."); }
        var requirements = new ArrayList<Requirement>();
        var notes = new ArrayList<String>();
        var skills = vocabulary.suggest(text);
        skills.forEach(s -> requirements.add(new Requirement(Kind.SKILL, s.id(), 0, Priority.OPTIONAL, false)));
        // Reuse conservative degree and named-certification recognition; CV work dates are not job minima.
        var parsed = new smarthire.cv.CvExtractor().suggest(text, vocabulary, java.time.YearMonth.of(2000, 1));
        if (parsed.degree() != null) {
            requirements.add(new Requirement(Kind.DEGREE, parsed.degree().name(), 0, Priority.OPTIONAL, false));
            if (!parsed.field().isBlank() && parsed.field().length() <= 180) {
                requirements.add(new Requirement(Kind.FIELD, parsed.field(), 0, Priority.OPTIONAL, false));
            }
        }
        parsed.certifications().stream().map(String::strip).filter(c -> !c.isEmpty() && c.length() <= 180).distinct()
                .forEach(c -> requirements.add(new Requirement(Kind.CERTIFICATION, c, 0, Priority.OPTIONAL, false)));
        var experience = Pattern.compile("(?i)\\b(\\d{1,3})\\s*(years?|months?)\\s+(?:of\\s+)?(?:(?:relevant|work|professional)\\s+)?experience\\b").matcher(text);
        if (experience.find()) {
            int months = Integer.parseInt(experience.group(1)) * (experience.group(2).toLowerCase(java.util.Locale.ROOT).startsWith("year") ? 12 : 1);
            if (months > 0 && months <= 1200 && !skills.isEmpty()) {
                requirements.add(new Requirement(Kind.EXPERIENCE, "", months, Priority.OPTIONAL, false));
                skills.forEach(s -> requirements.add(new Requirement(Kind.EXPERIENCE_TAG, s.id(), 0, Priority.OPTIONAL, false)));
                notes.add("Experience tags are suggestions from mentioned skills. Keep only the skills that define relevant work.");
            } else { notes.add("Review experience manually: a positive minimum up to 1,200 months needs explicit relevance skills."); }
        }
        notes.add("Review every suggested row, degree/field and certification. Unknown or ambiguous requirements need manual entry.");
        notes.add("All suggestions start optional and nonmandatory, even if the source says required or must. Only your submitted structured form sets those flags.");
        return new RequirementSuggestions(requirements, notes);
    }
}
