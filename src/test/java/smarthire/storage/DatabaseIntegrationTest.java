package smarthire.storage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import smarthire.identity.IdentityService;
import smarthire.identity.Role;
import smarthire.identity.UserError;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "smarthire.integration", matches = "true")
class DatabaseIntegrationTest {
    @Test
    void persistsProfilesAndEnforcesUniqueAccountsAndRoleRows() throws Exception {
        Database database = Database.load(Path.of(".local/application.properties"), true);
        database.initialize();
        JdbcAccounts repository = new JdbcAccounts(database);
        IdentityService service = new IdentityService(repository);
        List<Long> created = new ArrayList<>();
        try {
            for (Role role : List.of(Role.STUDENT, Role.COMPANY)) {
                String email = UUID.randomUUID() + "@integration.example";
                var profile = service.register(email, "Integration-test!21".toCharArray(), role, "Fictional Test");
                created.add(profile.id());
                assertThrows(UserError.class, () -> service.register(email.toUpperCase(java.util.Locale.ROOT),
                        "Integration-test!21".toCharArray(), role, "Duplicate"));
                UUID token = service.login(email, "Integration-test!21".toCharArray());
                service.saveProfile(token, "Saved Name", "123", "Test Organization", "Saved description");
                IdentityService restarted = new IdentityService(new JdbcAccounts(database));
                var saved = restarted.profile(restarted.login(email, "Integration-test!21".toCharArray()));
                assertEquals("Saved Name", saved.name());
                assertEquals("Saved description", saved.about());
                assertEquals(role, saved.role());
                try (var connection = database.connect(); var statement = connection.prepareStatement(
                        role == Role.STUDENT ? "SELECT user_id FROM students WHERE user_id = ?"
                                : "SELECT user_id FROM companies WHERE user_id = ?")) {
                    statement.setLong(1, profile.id());
                    try (var result = statement.executeQuery()) {
                        assertTrue(result.next());
                        assertEquals(profile.id(), result.getLong(1));
                        assertFalse(result.next());
                    }
                }
                try (var connection = database.connect(); var statement = connection.prepareStatement(
                        "UPDATE users SET active = FALSE WHERE id = ?")) {
                    statement.setLong(1, profile.id());
                    statement.executeUpdate();
                }
                assertThrows(UserError.class, () -> service.profile(token));
            }
        } finally {
            // Only remove the random accounts created by this test, in the dedicated test schema.
            try (var connection = database.connect()) {
                for (long id : created) {
                    for (String table : List.of("students", "companies", "users")) {
                        try (var statement = connection.prepareStatement("DELETE FROM " + table
                                + (table.equals("users") ? " WHERE id = ?" : " WHERE user_id = ?"))) {
                            statement.setLong(1, id);
                            statement.executeUpdate();
                        }
                    }
                }
            }
        }
    }
}
