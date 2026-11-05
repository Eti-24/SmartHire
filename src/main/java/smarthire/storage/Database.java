package smarthire.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import smarthire.identity.UserError;

public final class Database {
    private final String url;
    private final Properties credentials;

    private Database(String url, Properties credentials) {
        this.url = url;
        this.credentials = credentials;
    }

    public static Database load(Path file, boolean test) {
        return loadSchema(file, test ? "smarthire_test" : "smarthire");
    }

    /** Package-private seam for the isolated clean-database verification tool. */
    static Database loadSchema(Path file, String schema) {
        if (!schema.matches("smarthire|smarthire_test|smarthire_smoke_[a-f0-9]{32}")) { throw new IllegalArgumentException("Unexpected database name."); }
        Properties config = new Properties();
        try (var input = Files.newInputStream(file)) {
            config.load(input);
        } catch (IOException exception) {
            throw new UserError("Database configuration is missing or unreadable. Run scripts/run.ps1 from the project folder.");
        }
        Properties credentials = new Properties();
        credentials.setProperty("user", config.getProperty("user", ""));
        credentials.setProperty("password", config.getProperty("password", ""));
        return new Database("jdbc:mysql://127.0.0.1:3306/" + schema
                + "?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectTimeout=3000&socketTimeout=10000",
                credentials);
    }

    public Connection connect() throws SQLException {
        return DriverManager.getConnection(url, credentials);
    }

    public void initialize() {
        try (var input = Database.class.getResourceAsStream("/db/schema.sql")) {
            if (input == null) {
                throw new IllegalStateException("Packaged schema is missing.");
            }
            String sql = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            try (Connection connection = connect(); var statement = connection.createStatement()) {
                for (String command : sql.split(";")) {
                    if (!command.isBlank()) {
                        statement.execute(command);
                    }
                }
            }
        } catch (SQLException exception) {
            throw unavailable();
        } catch (IOException exception) {
            throw new UserError("Could not read the packaged database schema. Rebuild the application.");
        }
    }

    public static UserError unavailable() {
        return new UserError("Cannot access the SmartHire database. Start MySQL with scripts/start-mysql.ps1 and check the local database configuration.");
    }
}
