package smarthire.matching;

import java.text.Normalizer;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import smarthire.cv.CvProfile;
import smarthire.identity.UserError;
import smarthire.jobs.Job;
import smarthire.skills.Skill;
import smarthire.skills.SkillVocabulary;
import static smarthire.matching.ScoringPolicy.Category.*;

/** Pure scoring of confirmed evidence. Never reads free text, the clock, or mutable profiles. */
public final class MatchEngine {
    public MatchResult assess(CvProfile.Qualifications cv, Job.Details job, SkillVocabulary vocabulary, ScoringPolicy policy) {
        var buckets = new EnumMap<ScoringPolicy.Category, Bucket>(ScoringPolicy.Category.class);
        var checks = new ArrayList<MatchResult.MandatoryCheck>();
        var gaps = new ArrayList<MatchResult.Gap>();
        var suggestions = new ArrayList<String>();
        Set<String> skills = canonicalSkills(cv.skills(), vocabulary);
        Set<String> projectSkills = new HashSet<>();
        cv.projects().forEach(p -> projectSkills.addAll(canonicalSkills(p.skills(), vocabulary)));
        Set<String> certificates = new HashSet<>();
        cv.certifications().forEach(c -> certificates.add(normalize(c)));
        Set<String> tags = new java.util.TreeSet<>();
        job.requirements().stream().filter(r -> r.kind() == Job.Kind.EXPERIENCE_TAG)
                .forEach(r -> tags.add(skillId(r.value(), vocabulary)));
        long months = relevantMonths(cv.experience(), tags, vocabulary);
        Job.Requirement field = job.requirements().stream().filter(r -> r.kind() == Job.Kind.FIELD).findFirst().orElse(null);
        boolean fieldSatisfied = field == null || normalize(cv.field()).equals(normalize(field.value()));
        for (Job.Requirement r : job.requirements()) {
            boolean satisfied;
            String label;
            ScoringPolicy.Category category;
            double fraction;
            double weight = 1;
            switch (r.kind()) {
                case SKILL, PROJECT -> {
                    String id = skillId(r.value(), vocabulary);
                    Skill skill = vocabulary.findById(id).orElseThrow();
                    boolean project = r.kind() == Job.Kind.PROJECT;
                    satisfied = (project ? projectSkills : skills).contains(id);
                    label = (project ? "Project evidence: " : "Skill: ") + skill.name() + " (" + skill.type() + ")";
                    category = project ? PROJECTS : SKILLS;
                    fraction = satisfied ? 1 : 0;
                    if (!project) { weight = r.priority() == Job.Priority.REQUIRED ? 2 : 1; }
                    if (!satisfied) { suggestions.add("Review or develop " + (project ? "project evidence for " : "the skill ") + skill.name() + "."); }
                }
                case DEGREE -> {
                    Job.Degree minimum = Job.Degree.valueOf(r.value());
                    satisfied = cv.degree() != null && cv.degree().ordinal() >= minimum.ordinal();
                    label = "Minimum degree: " + minimum;
                    category = EDUCATION;
                    fraction = satisfied && fieldSatisfied ? 1 : 0;
                    if (fraction == 0) { suggestions.add("Review the minimum degree and specified field against your confirmed education."); }
                }
                case FIELD -> {
                    satisfied = fieldSatisfied;
                    label = "Degree field: " + r.value();
                    if (r.mandatory()) { checks.add(new MatchResult.MandatoryCheck(label, satisfied)); }
                    if (!satisfied) { gaps.add(new MatchResult.Gap(r.kind(), label, r.mandatory())); }
                    continue;
                }
                case EXPERIENCE -> {
                    if (r.months() == 0) { continue; }
                    satisfied = months >= r.months();
                    label = "Relevant experience: " + months + "/" + r.months() + " months";
                    category = EXPERIENCE;
                    fraction = Math.min(1, (double) months / r.months());
                    if (!satisfied) { suggestions.add("Review dated work tagged with a relevance skill; " + (r.months() - months) + " more distinct months are needed."); }
                }
                case CERTIFICATION -> {
                    satisfied = certificates.contains(normalize(r.value()));
                    label = "Certification: " + r.value();
                    category = CERTIFICATIONS;
                    fraction = satisfied ? 1 : 0;
                    if (!satisfied) { suggestions.add("Review or obtain the listed certification: " + r.value() + "."); }
                }
                case EXPERIENCE_TAG -> { continue; }
                default -> throw new IllegalArgumentException("Unsupported requirement");
            }
            Bucket bucket = buckets.computeIfAbsent(category, ignored -> new Bucket());
            bucket.earned += fraction * weight;
            bucket.total += weight;
            (satisfied ? bucket.matched : bucket.missing).add(label);
            if (category == EDUCATION && field != null) {
                (fieldSatisfied ? bucket.matched : bucket.missing).add("Degree field: " + field.value());
            }
            if (r.mandatory()) { checks.add(new MatchResult.MandatoryCheck(label, satisfied)); }
            if (!satisfied) { gaps.add(new MatchResult.Gap(r.kind(), label, r.mandatory())); }
        }
        long totalWeight = buckets.keySet().stream().mapToLong(c -> policy.weights().getOrDefault(c, 0)).sum();
        if (totalWeight <= 0) { throw new UserError("Active job requirements have no positive scoring weight."); }
        var contributions = new ArrayList<MatchResult.Contribution>();
        double score = 0;
        for (var entry : buckets.entrySet()) {
            Bucket b = entry.getValue();
            double fraction = b.earned / b.total;
            double normalizedWeight = (double) policy.weights().getOrDefault(entry.getKey(), 0) / totalWeight;
            double points = 100 * fraction * normalizedWeight;
            score += points;
            contributions.add(new MatchResult.Contribution(entry.getKey(), fraction, normalizedWeight, points, b.matched, b.missing));
        }
        return new MatchResult(policy.version(), score, contributions, checks, suggestions, months,
                tags.stream().map(id -> vocabulary.findById(id).orElseThrow().name()).toList(), gaps);
    }

    private static long relevantMonths(List<CvProfile.Experience> entries, Set<String> tags, SkillVocabulary vocabulary) {
        for (var entry : entries) {
            if (entry.start() == null || entry.end() == null || entry.start().isAfter(entry.end())) {
                throw new UserError("Matching requires confirmed work dates in order with a frozen end month.");
            }
        }
        var relevant = entries.stream().filter(e -> canonicalSkills(e.skills(), vocabulary).stream().anyMatch(tags::contains))
                .sorted(Comparator.comparing(CvProfile.Experience::start)).toList();
        java.time.YearMonth start = null, end = null;
        long months = 0;
        for (var entry : relevant) {
            if (start == null) { start = entry.start(); end = entry.end(); }
            else if (!entry.start().isAfter(end.plusMonths(1))) {
                if (entry.end().isAfter(end)) { end = entry.end(); }
            } else {
                months += ChronoUnit.MONTHS.between(start, end) + 1;
                start = entry.start(); end = entry.end();
            }
        }
        return months + (start == null ? 0 : ChronoUnit.MONTHS.between(start, end) + 1);
    }

    private static Set<String> canonicalSkills(List<String> values, SkillVocabulary vocabulary) {
        Set<String> result = new HashSet<>();
        values.forEach(value -> result.add(skillId(value, vocabulary)));
        return result;
    }

    private static String skillId(String value, SkillVocabulary vocabulary) {
        return vocabulary.findById(value).or(() -> vocabulary.resolve(value)).map(Skill::id)
                .orElseThrow(() -> new UserError("Unknown skill in matching evidence: " + value));
    }

    private static String normalize(String value) {
        return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("(?U)\\s+", " ").strip();
    }

    private static final class Bucket {
        double earned, total;
        final List<String> matched = new ArrayList<>(), missing = new ArrayList<>();
    }
}
