package smarthire.matching;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import smarthire.cv.CvProfile.*;
import smarthire.identity.UserError;
import smarthire.jobs.Job.*;
import smarthire.skills.SkillVocabulary;
import static org.junit.jupiter.api.Assertions.*;
import static smarthire.matching.ScoringPolicy.Category.*;

class MatchEngineTest {
    private final MatchEngine engine = new MatchEngine();
    private final SkillVocabulary vocabulary = SkillVocabulary.demo();

    private MatchResult assess(Qualifications cv, Requirement... requirements) {
        return engine.assess(cv, details(requirements), vocabulary, ScoringPolicy.initial());
    }

    private Details details(Requirement... requirements) {
        return new Details("Fictional role", "Description", 1, "Dhaka", BigDecimal.ZERO, BigDecimal.TEN, List.of(requirements));
    }

    private Requirement requirement(Kind kind, String value, boolean mandatory) {
        return new Requirement(kind, value, 0, Priority.REQUIRED, mandatory);
    }

    private Qualifications cv(List<String> skills) {
        return new Qualifications(skills, Degree.BACHELOR, "computer science", List.of(), List.of(), List.of());
    }

    @Test void highPercentageNeverOverridesMissingMandatorySkillAndRequiredIsNotAutomaticallyMandatory() {
        var requirements = List.of(requirement(Kind.SKILL, "java", false), requirement(Kind.SKILL, "sql", false),
                requirement(Kind.SKILL, "c", false), requirement(Kind.SKILL, "cpp", false),
                requirement(Kind.SKILL, "csharp", false), requirement(Kind.SKILL, "communication", false),
                requirement(Kind.SKILL, "teamwork", false), requirement(Kind.SKILL, "problem-solving", false),
                new Requirement(Kind.SKILL, "javascript", 0, Priority.OPTIONAL, true));
        var result = assess(cv(List.of("java", "sql", "c", "cpp", "csharp", "communication", "teamwork", "problem-solving")),
                requirements.toArray(Requirement[]::new));
        assertEquals(100 * 16.0 / 17, result.percentage(), 1e-10);
        assertFalse(result.eligible());
        assertEquals(1, result.failedMandatory().size());
        assertTrue(result.failedMandatory().getFirst().contains("JavaScript"));
        assertTrue(assess(cv(List.of()), requirement(Kind.SKILL, "java", false)).eligible());
    }

    @Test void requiredAndOptionalSkillsUseTwoToOneWeightsAndAbsentCategoriesRedistribute() {
        var result = assess(cv(List.of("Java")), requirement(Kind.SKILL, "java", false),
                new Requirement(Kind.SKILL, "sql", 0, Priority.OPTIONAL, false));
        assertEquals(100 * 2.0 / 3, result.percentage(), 1e-10);
        assertEquals(1, result.contributions().size());
        assertEquals(1, result.contributions().getFirst().normalizedWeight());
        assertEquals("default-v1", result.policyVersion());
    }

    @Test void aliasesResolveExactlyAcrossSkillsProjectsAndWorkWithoutSubstringMatches() {
        var qualifications = new Qualifications(List.of("JS", "Communication skills", "CPP"), null, "",
                List.of(new Project("Evidence", List.of("ECMAScript"))),
                List.of(work("2025-01", "2025-01", List.of("JS"))), List.of());
        var result = assess(qualifications, requirement(Kind.SKILL, "javascript", true),
                requirement(Kind.SKILL, "communication", true), requirement(Kind.PROJECT, "JS", true),
                new Requirement(Kind.EXPERIENCE, "", 1, Priority.REQUIRED, true), requirement(Kind.EXPERIENCE_TAG, "javascript", false));
        assertEquals(100, result.percentage(), 1e-10);
        assertTrue(result.eligible());
        var distinct = assess(qualifications, requirement(Kind.SKILL, "java", true), requirement(Kind.SKILL, "c", true));
        assertEquals(0, distinct.percentage());
        assertEquals(2, distinct.failedMandatory().size());
        assertTrue(result.contributions().stream().flatMap(c -> c.matched().stream()).anyMatch(s -> s.contains("SOFT")));
    }

    @Test void educationRequiresOrderedDegreeAndExactNormalizedFieldWithNoInferredEquivalence() {
        var minimum = requirement(Kind.DEGREE, "BACHELOR", true);
        var field = requirement(Kind.FIELD, " Computer  Science ", true);
        assertEquals(100, assess(cv(List.of()), minimum, field).percentage());
        var higher = new Qualifications(List.of(), Degree.MASTER, "COMPUTER SCIENCE", List.of(), List.of(), List.of());
        assertTrue(assess(higher, minimum, field).eligible());
        var abbreviation = new Qualifications(List.of(), Degree.MASTER, "CS", List.of(), List.of(), List.of());
        var mismatch = assess(abbreviation, minimum, field);
        assertEquals(0, mismatch.percentage());
        assertEquals(1, mismatch.failedMandatory().size());
        assertFalse(assess(Qualifications.empty(), minimum, field).eligible());
        assertEquals(0, assess(new Qualifications(List.of(), Degree.DIPLOMA, "computer science", List.of(), List.of(), List.of()), minimum, field).percentage());
    }

    @Test void overlappingAndDuplicateTaggedRangesCountDistinctInclusiveMonthsAndCapAtOne() {
        var qualification = new Qualifications(List.of(), null, "", List.of(), List.of(
                work("2025-03", "2025-06", List.of("java", "sql")),
                work("2025-01", "2025-04", List.of("java")), work("2025-01", "2025-04", List.of("java")),
                work("2024-01", "2025-12", List.of()), work("2024-01", "2025-12", List.of("c"))), List.of());
        var result = assess(qualification, new Requirement(Kind.EXPERIENCE, "", 4, Priority.REQUIRED, true),
                requirement(Kind.EXPERIENCE_TAG, "java", false), requirement(Kind.EXPERIENCE_TAG, "sql", false));
        assertEquals(6, result.relevantMonths());
        assertEquals(100, result.percentage());
        assertTrue(result.eligible());
        var partial = assess(qualification, new Requirement(Kind.EXPERIENCE, "", 12, Priority.REQUIRED, true),
                requirement(Kind.EXPERIENCE_TAG, "java", false));
        assertEquals(50, partial.percentage());
        assertFalse(partial.eligible());
        assertTrue(partial.suggestions().getFirst().contains("6 more"));
    }

    @Test void disjointAdjacentSingleAndFrozenOngoingMonthsRemainDeterministic() {
        var qualification = new Qualifications(List.of(), null, "", List.of(), List.of(
                work("2025-01", "2025-01", List.of("java")), work("2025-02", "2025-03", List.of("java")),
                new Experience("Ongoing frozen at review", YearMonth.of(2026, 1), YearMonth.of(2026, 2), true, List.of("java"))), List.of());
        var requirements = new Requirement[]{new Requirement(Kind.EXPERIENCE, "", 5, Priority.REQUIRED, true), requirement(Kind.EXPERIENCE_TAG, "java", false)};
        var result = assess(qualification, requirements);
        assertEquals(5, result.relevantMonths());
        assertEquals(result, assess(qualification, requirements));
        assertFalse(assess(Qualifications.empty(), requirements).eligible());
    }

    @Test void generalSkillsDoNotBecomeProjectsAndCertificatesUseNormalizedExactNames() {
        var qualification = new Qualifications(List.of("java"), null, "", List.of(), List.of(), List.of(" Ｄｅｍｏ  Certificate "));
        var result = assess(qualification, requirement(Kind.PROJECT, "java", true), requirement(Kind.CERTIFICATION, "demo certificate", true));
        assertFalse(result.eligible());
        assertEquals(40, result.percentage(), 1e-10);
        assertEquals(1, result.failedMandatory().size());
        var evidence = new Qualifications(List.of(), null, "", List.of(new Project("Demo", List.of("java"))), List.of(), List.of());
        assertEquals(100, assess(evidence, requirement(Kind.PROJECT, "java", true)).percentage());
    }

    @Test void contributionsUseDefaultActiveWeightsAndRetainUnroundedPrecision() {
        var result = assess(cv(List.of("java")), requirement(Kind.SKILL, "java", false),
                new Requirement(Kind.SKILL, "sql", 0, Priority.OPTIONAL, false), requirement(Kind.DEGREE, "BACHELOR", false));
        assertEquals(100 * ((2.0 / 3) * (40.0 / 60) + 20.0 / 60), result.percentage(), 1e-10);
        assertEquals(result.percentage(), result.contributions().stream().mapToDouble(MatchResult.Contribution::percentagePoints).sum(), 1e-10);
        assertNotEquals(Math.round(result.percentage() * 100) / 100.0, result.percentage());
        var zeroExperience = assess(cv(List.of("java")), requirement(Kind.SKILL, "java", false), new Requirement(Kind.EXPERIENCE, "", 0, Priority.REQUIRED, false));
        assertEquals(1, zeroExperience.contributions().size());
    }

    @Test void rejectsInvalidOrIncompatiblePoliciesAndAppliesAlternatePolicyWithoutChangingEvidence() {
        assertThrows(UserError.class, () -> new ScoringPolicy("bad", Map.of(SKILLS, -1, EDUCATION, 2)));
        assertThrows(UserError.class, () -> new ScoringPolicy("bad", Map.of(SKILLS, 0)));
        var incompatible = new ScoringPolicy("education-only", Map.of(EDUCATION, 1));
        assertThrows(UserError.class, () -> engine.assess(cv(List.of()), details(requirement(Kind.SKILL, "java", false)), vocabulary, incompatible));
        assertThrows(UserError.class, () -> assess(Qualifications.empty()));
        var details = details(requirement(Kind.SKILL, "java", true), requirement(Kind.DEGREE, "BACHELOR", false));
        var custom = engine.assess(cv(List.of()), details, vocabulary, incompatible);
        assertEquals(100, custom.percentage());
        assertFalse(custom.eligible());
        assertEquals("education-only", custom.policyVersion());
    }

    @Test void allFiveCategoriesExplainPartialProjectsCertificationsAndExperienceUnderDefaultWeights() {
        var qualification = new Qualifications(List.of("java"), Degree.BACHELOR, "computer science",
                List.of(new Project("Demo", List.of("java"))),
                List.of(work("2025-01", "2025-03", List.of("java"))), List.of("demo certificate"));
        var result = assess(qualification, requirement(Kind.SKILL, "java", false), requirement(Kind.SKILL, "sql", false),
                requirement(Kind.DEGREE, "BACHELOR", false), new Requirement(Kind.EXPERIENCE, "", 6, Priority.REQUIRED, false),
                requirement(Kind.EXPERIENCE_TAG, "java", false), requirement(Kind.PROJECT, "java", false), requirement(Kind.PROJECT, "sql", false),
                requirement(Kind.CERTIFICATION, "demo certificate", false), requirement(Kind.CERTIFICATION, "other certificate", false));
        assertEquals(5, result.contributions().size());
        assertEquals(60, result.percentage(), 1e-10); // 20 + 20 + 7.5 + 7.5 + 5
        assertTrue(result.eligible());
        assertEquals(4, result.suggestions().size());
        assertEquals(1, result.contributions().stream().mapToDouble(MatchResult.Contribution::normalizedWeight).sum(), 1e-10);
    }

    @Test void unconfirmedWorkWithMissingOrReversedDatesCannotProduceAnExperienceScore() {
        for (Experience work : List.of(new Experience("Unconfirmed", null, YearMonth.of(2025, 1), false, List.of("java")),
                work("2025-06", "2025-01", List.of("java")),
                new Experience("Ongoing unfrozen", YearMonth.of(2025, 1), null, true, List.of("java")))) {
            var qualification = new Qualifications(List.of(), null, "", List.of(), List.of(work), List.of());
            assertThrows(UserError.class, () -> assess(qualification, new Requirement(Kind.EXPERIENCE, "", 6, Priority.REQUIRED, true),
                    requirement(Kind.EXPERIENCE_TAG, "java", false)));
        }
    }

    private Experience work(String start, String end, List<String> tags) {
        return new Experience("Fictional work", YearMonth.parse(start), YearMonth.parse(end), false, tags);
    }
}
