package smarthire;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import smarthire.identity.UserError;
import smarthire.matching.ScoringPolicy;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "smarthire.integration", matches = "true")
class DemoPolicyIntegrationTest {
    @TempDir Path root;
    @Test void demoPublicationCannotBypassActivePolicyAndRollsBackIncompatibleFixtures() throws Exception {
        try (var f = new WorkflowFixture(root)) {
            var policy = f.jobs.activatePolicy(f.admin, Map.of(ScoringPolicy.Category.EDUCATION, 1));
            f.policies.add(policy.version());
            assertThrows(UserError.class, () -> DemoJobs.provision(f.database, f.companyId));
            assertTrue(f.jobs.list(f.company, "", null).isEmpty());
            assertEquals(policy, f.jobs.scoringPolicy(f.admin));
        }
    }
}
