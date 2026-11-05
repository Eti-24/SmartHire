package smarthire.cv;

import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;
import smarthire.jobs.Job.Degree;
import smarthire.skills.SkillVocabulary;
import static org.junit.jupiter.api.Assertions.*;

class CvExtractorTest {
    private final CvExtractor extractor = new CvExtractor();
    private final YearMonth month = YearMonth.of(2026, 11);

    @Test void cvSuggestionsPassTheSharedSkillPhraseFixture() throws Exception {
        try (var input = getClass().getResourceAsStream("/skills/phrases.tsv")) {
            for (String line : new String(input.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                if (line.startsWith("#")) { continue; }
                String[] columns = line.split("\t", -1);
                List<String> expected = columns[1].equals("-") ? List.of() : List.of(columns[1].split(","));
                assertEquals(expected, extractor.suggest(columns[0], SkillVocabulary.demo(), month).skills(), columns[0]);
            }
        }
    }

    @Test void suggestsSeparateProjectEvidenceDatedExperienceAndNamedQualifications() {
        var review = extractor.suggest("""
                Bachelor in Computer Science
                Skills: Java, SQL and communication
                Project: Student portal with Java and SQL
                Intern: 2025-01 to 2025-06, Java
                Developer: 2026-01 to present, SQL
                Certification: Fictional SQL Certificate
                """, SkillVocabulary.demo(), month);
        assertEquals(Degree.BACHELOR, review.degree());
        assertEquals("Computer Science", review.field());
        assertEquals(List.of("java", "sql"), review.projects().getFirst().skills());
        assertEquals(List.of("java"), review.experience().getFirst().skills());
        assertEquals(month, review.experience().get(1).end());
        assertTrue(review.experience().get(1).ongoing());
        assertEquals(List.of("Fictional SQL Certificate"), review.certifications());
        assertThrows(UnsupportedOperationException.class, () -> review.skills().add("cpp"));
    }

    @Test void unknownAndInvalidEvidenceRemainsForManualReviewWithoutInventedQualifications() {
        var review = extractor.suggest("""
                Python enthusiast with an excellent personality
                Worked for several years
                Intern 2025-12 to 2025-01
                Developer 2027-01 to present
                Intern 2025-13 to 2026-01
                Java listed as a general skill
                Education: 2020-01 to 2024-06
                """, SkillVocabulary.demo(), month);
        assertEquals(List.of("java"), review.skills());
        assertTrue(review.projects().isEmpty());
        assertTrue(review.experience().isEmpty());
        assertTrue(review.certifications().isEmpty());
        assertNull(review.degree());
    }
}
