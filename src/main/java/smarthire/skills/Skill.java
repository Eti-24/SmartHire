package smarthire.skills;

import java.util.Objects;
import java.util.Set;

/** A stable ID shared by job requirements, reviewed CVs, projects and experience tags. */
public record Skill(String id, String name, Type type, Set<String> aliases) {
    public enum Type { TECHNICAL, SOFT }

    public Skill {
        if (id == null || (!id.matches("[a-z][a-z0-9-]*") || id.length() > 80)) {
            throw new IllegalArgumentException("Skill IDs must be lowercase words separated by hyphens.");
        }
        if (name == null || name.isBlank() || name.length() > 180) {
            throw new IllegalArgumentException("A skill name is required.");
        }
        name = name.strip();
        Objects.requireNonNull(type, "Skill type is required.");
        aliases = Set.copyOf(aliases);
        if (aliases.stream().anyMatch(alias -> alias.isBlank() || alias.length() > 180)) {
            throw new IllegalArgumentException("Skill aliases must not be blank.");
        }
    }
}
