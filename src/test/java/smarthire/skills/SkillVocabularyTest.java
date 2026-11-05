package smarthire.skills;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SkillVocabularyTest {
    private final SkillVocabulary vocabulary = SkillVocabulary.demo();

    @Test
    void sharedPhraseFixtureReturnsCanonicalSkills() throws Exception {
        try (var reader = new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream("/skills/phrases.tsv"), StandardCharsets.UTF_8))) {
            for (String line : reader.lines().toList()) {
                if (line.startsWith("#") || line.startsWith("\uFEFF#")) {
                    continue;
                }
                String[] columns = line.split("\t", -1);
                assertEquals(2, columns.length, line);
                List<String> expected = columns[1].equals("-") ? List.of() : List.of(columns[1].split(","));
                assertEquals(expected, vocabulary.suggest(columns[0]).stream().map(Skill::id).toList(), columns[0]);
            }
        }
    }

    @Test
    void formValuesResolveExactlyAndUseTheSameIdsAsSuggestions() {
        assertEquals("javascript", vocabulary.resolve("  jS ").orElseThrow().id());
        assertEquals("sql", vocabulary.resolve("structured\n QUERY\t language").orElseThrow().id());
        assertTrue(vocabulary.resolve("Java developer").isEmpty());
        assertTrue(vocabulary.resolve("unknown").isEmpty());
        assertTrue(vocabulary.resolve(null).isEmpty());
        assertEquals(List.of(), vocabulary.suggest(null));
        assertEquals(Skill.Type.SOFT, vocabulary.resolve("TEAM WORK").orElseThrow().type());
        assertEquals(Skill.Type.TECHNICAL, vocabulary.findById("cpp").orElseThrow().type());
        assertTrue(vocabulary.findById(null).isEmpty());
    }

    @Test
    void matchingIsIndependentOfMachineLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("javascript", vocabulary.resolve("JAVASCRIPT").orElseThrow().id());
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void rejectsAmbiguousAliasesAndDuplicateIds() {
        Skill java = new Skill("java", "Java", Skill.Type.TECHNICAL, Set.of("JVM language"));
        assertThrows(IllegalArgumentException.class, () -> new SkillVocabulary(List.of(java, java)));
        Skill conflicting = new Skill("other", "Other", Skill.Type.TECHNICAL, Set.of(" JAVA "));
        assertThrows(IllegalArgumentException.class, () -> new SkillVocabulary(List.of(java, conflicting)));
        assertThrows(IllegalArgumentException.class, () -> new Skill("bad id", "Name", Skill.Type.SOFT, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new Skill("valid", " ", Skill.Type.SOFT, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new Skill("valid", "Name", Skill.Type.SOFT, Set.of(" ")));
    }

    @Test
    void longerCuratedPhrasesWinAndVocabularyResultsAreImmutable() {
        Skill communication = vocabulary.findById("communication").orElseThrow();
        Skill written = new Skill("written-communication", "Written communication", Skill.Type.SOFT, Set.of());
        var source = new java.util.ArrayList<>(List.of(communication, written));
        var custom = new SkillVocabulary(source);
        source.clear();
        assertEquals(List.of(written), custom.suggest("Written communication"));
        assertThrows(UnsupportedOperationException.class, () -> custom.skills().clear());
        assertThrows(UnsupportedOperationException.class, () -> custom.suggest("communication").clear());
        assertEquals(List.of(), new SkillVocabulary(List.of()).suggest("Java"));
    }
}
