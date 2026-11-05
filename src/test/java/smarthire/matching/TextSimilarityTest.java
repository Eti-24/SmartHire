package smarthire.matching;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TextSimilarityTest {
    @Test void identicalDisjointAndEmptyTextsHaveDefinedCosineResults() {
        var result = new TextSimilarity().compare("Java SQL", List.of("java sql", "gardening retail", ""));
        assertEquals(1, result.get(0), 1e-10); assertEquals(0, result.get(1)); assertEquals(0, result.get(2));
        assertEquals(List.of(0.0, 0.0), new TextSimilarity().compare("", List.of("java", "sql")));
    }
    @Test void sharedCorpusUsesSmoothedIdfAndDoesNotCollapseLanguageTokens() {
        var similarity = new TextSimilarity();
        var result = similarity.compare("java sql", List.of("java java", "sql"));
        assertEquals(Math.sqrt(0.5), result.get(0), 1e-10);
        assertEquals(result.get(0), result.get(1), 1e-10);
        assertEquals(0, similarity.compare("C Java", List.of("C++ JavaScript")).getFirst());
        assertEquals(result, similarity.compare("JAVA SQL", List.of("java java", "sql")));
    }
}
