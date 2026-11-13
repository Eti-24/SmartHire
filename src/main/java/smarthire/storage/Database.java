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
    private static final int SCHEMA_VERSION = 5;
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
            throw new UserError("Database configuration is missing or unreadable. Check " + file + " and follow the setup guide.");
        }
        Properties credentials = new Properties();
        credentials.setProperty("user", config.getProperty("user", ""));
        credentials.setProperty("password", config.getProperty("password", ""));
        String remoteUrl = config.getProperty("jdbcUrl");
        if (allowRemote && remoteUrl == null && Boolean.getBoolean("smarthire.requireCloud")) {
            throw new UserError("Set jdbcUrl in the cloud database configuration.");
        }
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
        String port = config.getProperty("port", "3306").trim();
        try {
            if (!port.matches("[0-9]{1,5}") || Integer.parseInt(port) < 1 || Integer.parseInt(port) > 65535) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException exception) {
            throw new UserError("Set port to a number from 1 to 65535 in the local database configuration.");
        }
        return new Database("jdbc:mysql://127.0.0.1:" + port + "/" + schema
                + "?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectTimeout=3000&socketTimeout=10000",
                credentials);
    }

    public Connection connect() throws SQLException {
        try {
            return DriverManager.getConnection(url, credentials);
        } catch (SQLException exception) {
            throw connectionFailure(exception);
        }
    }

    public boolean isCloud() {
        return "VERIFY_IDENTITY".equals(credentials.getProperty("sslMode"));
    }

    public void initialize() {
        if (isCloud()) {
            if (!Boolean.getBoolean("smarthire.requireCloud")) {
                throw new UserError("This launch is for local MySQL. Use the SmartHire cloud launcher to connect to Aiven.");
            }
            verifyCloudSchema();
            return;
        }
        applySchema();
    }

    public void upgradeCloudSchema() {
        if (!isCloud() || !Boolean.getBoolean("smarthire.requireCloud")) {
            throw new UserError("Use the SmartHire cloud launcher to upgrade Aiven.");
        }
        applySchema();
    }

    private void verifyCloudSchema() {
        try (var connection = connect(); var query = connection.prepareStatement(
                "SELECT COUNT(*) FROM schema_migrations WHERE version BETWEEN 1 AND ?")) {
            query.setInt(1, SCHEMA_VERSION);
            try (var result = query.executeQuery()) {
                if (result.next() && result.getInt(1) == SCHEMA_VERSION) { return; }
            }
        } catch (SQLException exception) {
            if (!"42S02".equals(exception.getSQLState())) { throw connectionFailure(exception); }
        }
        throw new UserError("The cloud schema needs an upgrade. Run SmartHire-Cloud.cmd --upgrade-database before signing in.");
    }

    private void applySchema() {
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
                applyMigration(connection, 2, "/db/migration-02-application-timeline.sql");
                if (!hasColumn(connection, "jobs", "application_deadline_at")) {
                    statement.execute("ALTER TABLE jobs ADD COLUMN application_deadline_at DATETIME(6) NULL,"
                            + " ADD COLUMN application_deadline_zone VARCHAR(80) NULL,"
                            + " ADD CONSTRAINT job_deadline_has_zone CHECK ((application_deadline_at IS NULL) = (application_deadline_zone IS NULL))");
                }
                statement.executeUpdate("INSERT IGNORE INTO schema_migrations(version) VALUES (3)");
                applyMigration(connection, 4, "/db/migration-04-saved-searches.sql");
                if (!hasColumn(connection, "users", "credential_version")) {
                    statement.execute("ALTER TABLE users ADD COLUMN credential_version BIGINT NOT NULL DEFAULT 1");
                }
                applyMigration(connection, 5, "/db/migration-05-recovery-codes.sql");
            }
        } catch (SQLException exception) {
            throw connectionFailure(exception);
        } catch (IOException exception) {
            throw new UserError("Could not read the packaged database schema. Rebuild the application.");
        }
    }

    private static void applyMigration(Connection connection, int version, String resource) throws SQLException, IOException {
        try (var check = connection.prepareStatement("SELECT version FROM schema_migrations WHERE version=?")) {
            check.setInt(1, version);
            try (var applied = check.executeQuery()) {
                if (applied.next()) { return; }
            }
        }
        try (var input = Database.class.getResourceAsStream(resource); var ddl = connection.createStatement();
                var mark = connection.prepareStatement("INSERT INTO schema_migrations(version) VALUES (?)")) {
            if (input == null) { throw new IOException("Migration resource missing."); }
            var sql = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            for (String command : sql.split(";")) {
                if (!command.isBlank()) { ddl.execute(command); }
            }
            mark.setInt(1, version);
            mark.executeUpdate();
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

    String connectionUrl() { return url; }

    UserError connectionFailure(SQLException exception) {
        String endpoint = url.substring("jdbc:mysql://".length()).split("\\?", 2)[0];
        String advice = switch (exception.getErrorCode()) {
            case 1045 -> "MySQL rejected the configured username or password. Check the account on this server.";
            case 1044, 1142, 1143, 1227 -> "The MySQL account lacks permission. Ask its administrator to grant access to the SmartHire database.";
            case 1049 -> "The SmartHire database is missing. Create it on this server using the setup guide.";
            default -> exception.getSQLState() != null && exception.getSQLState().startsWith("08")
                    ? "The server could not be reached. Check that it is running on the configured port."
                            + (isCloud() ? " Check the cloud network and certificate settings."
                                    : " If XAMPP uses 3306, configure SmartHire's separate MySQL server and its port.")
                    : "The database operation failed. Check MySQL permissions and the database setup.";
        };
        if (!isCloud()) {
            advice += " For a portable source setup, close SmartHire, run Client-Repair.cmd, then reopen it. For an application-only ZIP, follow its setup guide.";
        }
        return new UserError("Database connection: " + endpoint + ". " + advice);
    }

    public static UserError unavailable() {
        return new UserError("Cannot access the SmartHire database. Check the database configuration and make sure your MySQL server is reachable.");
    }
}
