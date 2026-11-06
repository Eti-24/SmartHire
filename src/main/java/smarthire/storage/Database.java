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
        return loadSchema(file, test ? "smarthire_test" : "smarthire", !test);
    }

    /** Package-private seam for the isolated clean-database verification tool. */
    static Database loadSchema(Path file, String schema) {
        return loadSchema(file, schema, false);
    }

    private static Database loadSchema(Path file, String schema, boolean allowRemote) {
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
        String remoteUrl = config.getProperty("jdbcUrl");
        if (allowRemote && remoteUrl != null) {
            String trustStore = config.getProperty("trustStore");
            if (!remoteUrl.matches("jdbc:mysql://[a-zA-Z0-9.-]+:[0-9]{1,5}/[a-zA-Z0-9_]+")
                    || trustStore == null || trustStore.isBlank()) {
                throw new UserError("Check the cloud database JDBC URL and trust store in the database configuration.");
            }
            Path trustStoreFile = file.toAbsolutePath().resolveSibling(trustStore);
            if (!Files.isReadable(trustStoreFile)) {
                throw new UserError("The cloud database trust store is missing or unreadable.");
            }
            credentials.setProperty("sslMode", "VERIFY_IDENTITY");
            credentials.setProperty("trustCertificateKeyStoreUrl", trustStoreFile.toUri().toString());
            credentials.setProperty("trustCertificateKeyStoreType", "PKCS12");
            credentials.setProperty("trustCertificateKeyStorePassword", config.getProperty("trustStorePassword", "changeit"));
            return new Database(remoteUrl + "?connectTimeout=5000&socketTimeout=10000", credentials);
        }
        return new Database("jdbc:mysql://127.0.0.1:3306/" + schema
                + "?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectTimeout=3000&socketTimeout=10000",
                credentials);
    }

    public Connection connect() throws SQLException {
        return DriverManager.getConnection(url, credentials);
    }

    public boolean isCloud() {
        return "VERIFY_IDENTITY".equals(credentials.getProperty("sslMode"));
    }

    public void initialize() {
        try (var input = Database.class.getResourceAsStream("/db/schema.sql")) {
            if (input == null) {
                throw new IllegalStateException("Packaged schema is missing.");
            }
            String sql = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            try (Connection connection = connect(); var statement = connection.createStatement()) {
                try (var lock = statement.executeQuery("SELECT GET_LOCK(CONCAT('schema:',DATABASE()),10)")) {
                    if (!lock.next() || lock.getInt(1) != 1) { throw new UserError("Another SmartHire session is preparing the database. Try again."); }
                }
                for (String command : sql.split(";")) {
                    if (!command.isBlank()) {
                        statement.execute(command);
                    }
                }
                migrateVacancies(connection);
            }
        } catch (SQLException exception) {
            throw unavailable();
        } catch (IOException exception) {
            throw new UserError("Could not read the packaged database schema. Rebuild the application.");
        }
    }

    private static void migrateVacancies(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            try (var applied = statement.executeQuery("SELECT version FROM schema_migrations WHERE version=1")) {
                if (applied.next()) { return; }
            }
            // MySQL commits each ALTER separately. Rechecking both columns makes an interrupted upgrade safe to retry.
            if (!hasColumn(connection, "jobs", "vacancy_count")) {
                statement.execute("ALTER TABLE jobs ADD COLUMN vacancy_count INT NOT NULL DEFAULT 1,"
                        + " ADD CONSTRAINT job_positive_vacancy_count CHECK (vacancy_count > 0)");
            }
            if (!hasColumn(connection, "applications", "hired_at")) {
                statement.execute("ALTER TABLE applications ADD COLUMN hired_at TIMESTAMP(6) NULL");
            }
            statement.executeUpdate("INSERT INTO schema_migrations(version) VALUES (1)");
        }
    }

    private static boolean hasColumn(Connection connection, String table, String column) throws SQLException {
        try (var columns = connection.getMetaData().getColumns(connection.getCatalog(), null, table, column)) {
            while (columns.next()) {
                if (column.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) { return true; }
            }
            return false;
        }
    }

    public static UserError unavailable() {
        return new UserError("Cannot access the SmartHire database. Check the database configuration and make sure your MySQL server is reachable.");
    }
}
