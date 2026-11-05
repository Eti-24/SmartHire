package smarthire.jobs;

import org.junit.jupiter.api.Test;
import smarthire.skills.SkillVocabulary;
import static org.junit.jupiter.api.Assertions.*;

class RequirementExtractorTest {
    private final RequirementExtractor extractor = new RequirementExtractor();
    @Test void recognizedSuggestionsRemainOptionalNonmandatoryAndEditableEvenWhenTextSaysMust() {
        var result = extractor.suggest("Must have JS, Java and communication skills.\nBachelor in Computer Science\n2 years of relevant experience\nCertification: Demo Certificate", SkillVocabulary.demo());
        assertTrue(result.requirements().stream().allMatch(r -> !r.mandatory() && r.priority() == Job.Priority.OPTIONAL));
        assertTrue(result.requirements().stream().anyMatch(r -> r.kind() == Job.Kind.SKILL && r.value().equals("javascript")));
        assertTrue(result.requirements().stream().anyMatch(r -> r.kind() == Job.Kind.EXPERIENCE && r.months() == 24));
        assertTrue(result.requirements().stream().anyMatch(r -> r.kind() == Job.Kind.CERTIFICATION && r.value().equals("Demo Certificate")));
        assertTrue(result.requirements().stream().anyMatch(r -> r.kind() == Job.Kind.DEGREE && r.value().equals("BACHELOR")));
        assertFalse(result.reviewNotes().isEmpty());
    }
    @Test void unknownAndAmbiguousTextRequiresManualReviewAndExperienceNeedsRelevanceTags() {
        var result = extractor.suggest("Quantum banana qualification; 3 years experience", SkillVocabulary.demo());
        assertTrue(result.requirements().isEmpty()); assertTrue(result.reviewNotes().size() >= 2);
        var boundary = extractor.suggest("JavaScript and C++", SkillVocabulary.demo());
        assertEquals(java.util.List.of("javascript", "cpp"), boundary.requirements().stream().map(Job.Requirement::value).toList());
    }
}
