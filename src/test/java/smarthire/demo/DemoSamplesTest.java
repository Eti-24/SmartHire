package smarthire.demo;

import java.nio.file.Path;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import smarthire.cv.CvExtractor;
import smarthire.documents.*;
import smarthire.matching.*;
import smarthire.skills.SkillVocabulary;
import static org.junit.jupiter.api.Assertions.*;

class DemoSamplesTest {
    @Test void actualDemoPdfsProduceTheDocumentedScoresAndIndependentEligibility() {
        String[] files = {"demo-cv-mandatory-gap.pdf", "demo-cv-skill-gap.pdf", "demo-cv-eligible.pdf"};
        double[] scores = {100.0 * 19 / 21, 100.0 * 3 / 7, 100};
        for (int i = 0; i < files.length; i++) {
            String text = new DocumentReader().read(Path.of("src/test/resources/demo", files[i]), StoredDocument.Format.PDF);
            var q = new CvExtractor().suggest(text, SkillVocabulary.demo(), YearMonth.of(2026, 11));
            var result = new MatchEngine().assess(q, DemoScenario.job(1), SkillVocabulary.demo(), ScoringPolicy.initial());
            assertEquals(scores[i], result.percentage(), 1e-9, files[i]); assertEquals(i != 0, result.eligible(), files[i]);
            if (i == 0) { assertFalse(q.skills().contains("java")); assertFalse(result.failedMandatory().isEmpty()); }
        }
    }
}
