package smarthire.jobs;

import java.util.List;

public record RequirementSuggestions(List<Job.Requirement> requirements, List<String> reviewNotes) {
    public RequirementSuggestions { requirements = List.copyOf(requirements); reviewNotes = List.copyOf(reviewNotes); }
}
