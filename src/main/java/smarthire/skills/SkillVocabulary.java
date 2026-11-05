package smarthire.skills;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Immutable vocabulary snapshot. Recognition suggests skills; it does not verify claims. */
public final class SkillVocabulary {
    private static final String TOKEN = "[\\p{L}\\p{N}_+#]";
    private final Map<String, Skill> byId;
    private final Map<String, Skill> byPhrase;
    private final Pattern phrases;

    public SkillVocabulary(Collection<Skill> skills) {
        Map<String, Skill> ids = new TreeMap<>();
        Map<String, Skill> names = new TreeMap<>();
        for (Skill skill : skills) {
            if (ids.putIfAbsent(skill.id(), skill) != null) {
                throw new IllegalArgumentException("Duplicate skill ID: " + skill.id());
            }
            List<String> labels = new ArrayList<>(skill.aliases());
            labels.add(skill.name());
            for (String label : labels) {
                String phrase = normalize(label);
                if (phrase.isEmpty()) {
                    throw new IllegalArgumentException("Skill phrases must not be blank.");
                }
                Skill previous = names.putIfAbsent(phrase, skill);
                if (previous != null && !previous.id().equals(skill.id())) {
                    throw new IllegalArgumentException("Ambiguous skill phrase: " + phrase);
                }
            }
        }
        byId = Map.copyOf(ids);
        byPhrase = Map.copyOf(names);
        // Prefer a complete curated phrase over a shorter skill embedded within it.
        String alternatives = names.keySet().stream()
                .sorted(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()))
                .map(Pattern::quote).collect(java.util.stream.Collectors.joining("|"));
        phrases = Pattern.compile(alternatives.isEmpty() ? "(?!)"
                : "(?<!" + TOKEN + ")(?:" + alternatives + ")(?!" + TOKEN + ")");
    }

    public List<Skill> skills() {
        return byId.values().stream().sorted(Comparator.comparing(Skill::id)).toList();
    }

    /** Resolve one complete form value, never a substring or an inferred synonym. */
    public Optional<Skill> resolve(String label) {
        return Optional.ofNullable(byPhrase.get(normalize(label)));
    }

    public Optional<Skill> findById(String id) {
        return Optional.ofNullable(id == null ? null : byId.get(id));
    }

    /** Return distinct suggestions in first-mention order, including technical and soft skills. */
    public List<Skill> suggest(String text) {
        Map<String, Skill> found = new LinkedHashMap<>();
        var matcher = phrases.matcher(normalize(text));
        while (matcher.find()) {
            Skill skill = byPhrase.get(matcher.group());
            found.putIfAbsent(skill.id(), skill);
        }
        return List.copyOf(found.values());
    }

    private static String normalize(String value) {
        return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("(?U)\\s+", " ").strip();
    }

    /** Small explicit demo catalog; unknown terms remain for manual review. */
    public static SkillVocabulary demo() {
        return new SkillVocabulary(List.of(
                new Skill("java", "Java", Skill.Type.TECHNICAL, Set.of()),
                new Skill("javascript", "JavaScript", Skill.Type.TECHNICAL, Set.of("JS", "ECMAScript")),
                new Skill("c", "C", Skill.Type.TECHNICAL, Set.of()),
                new Skill("cpp", "C++", Skill.Type.TECHNICAL, Set.of("CPP")),
                new Skill("csharp", "C#", Skill.Type.TECHNICAL, Set.of("C Sharp")),
                new Skill("sql", "SQL", Skill.Type.TECHNICAL, Set.of("Structured Query Language")),
                new Skill("communication", "Communication", Skill.Type.SOFT, Set.of("Communication skills")),
                new Skill("teamwork", "Teamwork", Skill.Type.SOFT, Set.of("Team work")),
                new Skill("problem-solving", "Problem solving", Skill.Type.SOFT, Set.of("Problem-solving"))));
    }
}
