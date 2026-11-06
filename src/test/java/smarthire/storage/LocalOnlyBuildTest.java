package smarthire.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import smarthire.identity.UserError;
import static org.junit.jupiter.api.Assertions.*;

class LocalOnlyBuildTest {
    @TempDir Path folder;

    @Test void refusesCloudInitializationBeforeOpeningAConnection() throws Exception {
        Files.createFile(folder.resolve("fixture.p12"));
        Path config = Files.writeString(folder.resolve("cloud.properties"),
                "jdbcUrl=jdbc:mysql://example.invalid:3306/defaultdb\ntrustStore=fixture.p12\n");
        Database cloud = Database.load(config, false);
        assertTrue(cloud.isCloud());
        assertTrue(assertThrows(UserError.class, cloud::initialize).getMessage().contains("for local MySQL"));
    }

    @Test void refusesCloudUpgradeWithLocalConfiguration() throws Exception {
        Path config = Files.createFile(folder.resolve("local.properties"));
        Database local = Database.load(config, true);
        assertTrue(assertThrows(UserError.class, local::upgradeCloudSchema).getMessage().contains("cloud launcher"));
    }
}
