package smarthire.matching;

import java.util.List;
import smarthire.jobs.Job;

/** Unrounded values and the evidence used to calculate them, independent of presentation. */
public record MatchResult(String policyVersion, double percentage, List<Contribution> contributions,
        List<MandatoryCheck> mandatoryChecks, List<String> suggestions, long relevantMonths,
        List<String> relevanceTags, List<Gap> gaps) {
    public MatchResult {
        contributions = List.copyOf(contributions);
        mandatoryChecks = List.copyOf(mandatoryChecks);
        suggestions = List.copyOf(suggestions);
        relevanceTags = List.copyOf(relevanceTags);
        gaps = List.copyOf(gaps);
    }

    public boolean eligible() { return mandatoryChecks.stream().allMatch(MandatoryCheck::satisfied); }
    public List<String> failedMandatory() {
        return mandatoryChecks.stream().filter(c -> !c.satisfied()).map(MandatoryCheck::requirement).toList();
    }

    public record Contribution(ScoringPolicy.Category category, double fraction, double normalizedWeight,
            double percentagePoints, List<String> matched, List<String> missing) {
        public Contribution { matched = List.copyOf(matched); missing = List.copyOf(missing); }
    }
    public record MandatoryCheck(String requirement, boolean satisfied) { }
    public record Gap(Job.Kind kind, String label, boolean mandatory) { }
}
