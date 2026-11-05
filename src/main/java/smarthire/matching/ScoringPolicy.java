package smarthire.matching;

import java.util.Map;
import smarthire.identity.UserError;

/** Immutable versioned calculation basis; omitted categories explicitly have zero weight. */
public record ScoringPolicy(String version, Map<Category, Integer> weights) {
    public enum Category { SKILLS, EDUCATION, EXPERIENCE, PROJECTS, CERTIFICATIONS }

    public ScoringPolicy {
        var complete = new java.util.EnumMap<Category, Integer>(Category.class);
        for (var category : Category.values()) { complete.put(category, weights.getOrDefault(category, 0)); }
        weights = Map.copyOf(complete);
        if (version == null || version.isBlank() || weights.values().stream().anyMatch(w -> w < 0)
                || weights.values().stream().noneMatch(w -> w > 0)) {
            throw new UserError("Scoring policy needs a version and nonnegative weights with a positive total.");
        }
    }

    public static ScoringPolicy initial() {
        return new ScoringPolicy("default-v1", Map.of(Category.SKILLS, 40, Category.EDUCATION, 20,
                Category.EXPERIENCE, 15, Category.PROJECTS, 15, Category.CERTIFICATIONS, 10));
    }
}
