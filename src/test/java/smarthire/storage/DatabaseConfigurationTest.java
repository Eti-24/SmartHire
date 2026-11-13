package smarthire.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import smarthire.identity.UserError;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseConfigurationTest {
    @TempDir Path directory;

    private Database load(String settings, boolean test) throws Exception {
        Path file = directory.resolve("application.properties");
        Files.writeString(file, "user=demo\npassword=private-value\n" + settings);
        return Database.load(file, test);
    }

    @Test void legacyConfigurationKeeps3306() throws Exception {
        assertTrue(load("", false).connectionUrl().startsWith("jdbc:mysql://127.0.0.1:3306/smarthire?"));
    }

    @Test void configuredPortAppliesToApplicationAndTestSchema() throws Exception {
        assertTrue(load("port=3307\n", false).connectionUrl().startsWith("jdbc:mysql://127.0.0.1:3307/smarthire?"));
        assertTrue(load("port=3307\n", true).connectionUrl().startsWith("jdbc:mysql://127.0.0.1:3307/smarthire_test?"));
    }

    @Test void invalidPortsAreRejectedBeforeConnecting() {
        for (String value : new String[]{"", "0", "65536", "-1", "3307/other", "abc"}) {
            assertThrows(UserError.class, () -> load("port=" + value + "\n", false), value);
        }
    }

    @Test void diagnosticsIdentifyEndpointAndCauseWithoutSecrets() throws Exception {
        Database database = load("port=3307\n", false);
        int[] codes = {1045, 1044, 1049, 1142, 0};
        String[] messages = {"username or password", "lacks permission", "database is missing", "lacks permission", "could not be reached"};
        for (int i = 0; i < codes.length; i++) {
            String message = database.connectionFailure(new SQLException("private-value", "08001", codes[i])).getMessage();
            assertTrue(message.contains("127.0.0.1:3307/smarthire"));
            assertTrue(message.contains(messages[i]), message);
            assertFalse(message.contains("private-value"));
        }
    }
}
