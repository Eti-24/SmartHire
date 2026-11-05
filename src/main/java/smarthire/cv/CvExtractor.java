package smarthire.cv;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import smarthire.jobs.Job.Degree;
import smarthire.skills.Skill;
import smarthire.skills.SkillVocabulary;
import static smarthire.cv.CvProfile.*;

/** Conservative English suggestions. Unknown text remains visible for manual review. */
public final class CvExtractor {
    private static final Pattern DATES = Pattern.compile("(\\d{4}-\\d{2})\\s*(?:to|–|—|-)\\s*(\\d{4}-\\d{2}|present|ongoing)", Pattern.CASE_INSENSITIVE);

    public Qualifications suggest(String text, SkillVocabulary vocabulary, YearMonth month) {
        List<String> skills = ids(vocabulary, text);
        Degree degree = null;
        String field = "";
        var projects = new ArrayList<Project>();
        var experience = new ArrayList<Experience>();
        var certifications = new ArrayList<String>();
        for (String line : text.split("\\R")) {
            String stripped = line.strip();
            String lower = stripped.toLowerCase(java.util.Locale.ROOT);
            Degree found = null;
            if (lower.matches(".*\\b(doctorate|phd|ph\\.d\\.)\\b.*")) { found = Degree.DOCTORATE; }
            else if (lower.matches(".*\\b(master|masters|msc|m\\.sc)\\b.*")) { found = Degree.MASTER; }
            else if (lower.matches(".*\\b(bachelor|bachelors|bsc|b\\.sc)\\b.*")) { found = Degree.BACHELOR; }
            else if (lower.matches(".*\\bdiploma\\b.*")) { found = Degree.DIPLOMA; }
            else if (lower.matches(".*\\b(secondary|high school)\\b.*")) { found = Degree.SECONDARY; }
            if (found != null && (degree == null || found.ordinal() > degree.ordinal())) {
                degree = found;
                var subject = Pattern.compile("(?i)\\bin\\s+(.+)").matcher(stripped);
                field = subject.find() ? subject.group(1).strip() : "";
            }
            if (lower.startsWith("project:") || lower.startsWith("project evidence:")) {
                projects.add(new Project(afterColon(stripped), ids(vocabulary, stripped)));
            }
            if (lower.startsWith("certification:") || lower.startsWith("certificate:")) {
                String certificate = afterColon(stripped);
                if (!certificate.isEmpty()) { certifications.add(certificate); }
            }
            var dates = DATES.matcher(stripped);
            if (dates.find() && lower.matches("^(experience|work|intern|internship|developer|engineer|analyst):.*")) {
                try {
                    YearMonth start = YearMonth.parse(dates.group(1));
                    boolean ongoing = dates.group(2).equalsIgnoreCase("present") || dates.group(2).equalsIgnoreCase("ongoing");
                    YearMonth end = ongoing ? month : YearMonth.parse(dates.group(2));
                    if (!start.isAfter(end) && !end.isAfter(month)) {
                        experience.add(new Experience(stripped, start, end, ongoing, ids(vocabulary, stripped)));
                    }
                } catch (java.time.DateTimeException ignored) { /* Leave ambiguous dates for manual entry. */ }
            }
        }
        return new Qualifications(skills, degree, field, projects, experience, certifications);
    }

    private static List<String> ids(SkillVocabulary vocabulary, String text) {
        return vocabulary.suggest(text).stream().map(Skill::id).toList();
    }

    private static String afterColon(String text) { return text.substring(text.indexOf(':') + 1).strip(); }
}
