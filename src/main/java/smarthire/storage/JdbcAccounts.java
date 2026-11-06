package smarthire.storage;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;
import smarthire.identity.Account;
import smarthire.identity.AccountRepository;
import smarthire.identity.Role;
import smarthire.identity.UserError;

public final class JdbcAccounts implements AccountRepository {
    private final Database database;

    public JdbcAccounts(Database database) {
        this.database = database;
    }

    @Override
    public Optional<Account> findByEmail(String email) {
        return find("SELECT * FROM users WHERE email = ?", email);
    }

    @Override
    public Optional<Account> findById(long id) {
        return find("SELECT * FROM users WHERE id = ?", id);
    }

    private Optional<Account> find(String sql, Object parameter) {
        try (Connection connection = database.connect(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, parameter);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(read(result)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw Database.unavailable();
        }
    }

    @Override
    public Account create(String email, String passwordHash, Role role, String name) {
        try (Connection connection = database.connect()) {
            connection.setAutoCommit(false);
            try {
                long id;
                try (var statement = connection.prepareStatement(
                        "INSERT INTO users(email, password_hash, role, display_name) VALUES (?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, email);
                    statement.setString(2, passwordHash);
                    statement.setString(3, role.name());
                    statement.setString(4, name);
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        if (!keys.next()) {
                            throw new SQLException("Missing generated account ID");
                        }
                        id = keys.getLong(1);
                    }
                }
                if (role != Role.ADMIN) {
                    String sql = role == Role.STUDENT ? "INSERT INTO students(user_id) VALUES (?)"
                            : "INSERT INTO companies(user_id) VALUES (?)";
                    try (var statement = connection.prepareStatement(sql)) {
                        statement.setLong(1, id);
                        statement.executeUpdate();
                    }
                }
                connection.commit();
                return new Account(id, email, passwordHash, role, true, name, "", "", "");
            } catch (SQLException exception) {
                connection.rollback();
                if (exception.getErrorCode() == 1062) {
                    throw new UserError("An account already uses this email address.");
                }
                throw exception;
            }
        } catch (SQLException exception) {
            throw Database.unavailable();
        }
    }

    @Override
    public void updateProfile(long id, String name, String phone, String affiliation, String about) {
        try (Connection connection = database.connect(); var statement = connection.prepareStatement(
                "UPDATE users SET display_name = ?, phone = ?, affiliation = ?, about = ? WHERE id = ? AND active = TRUE")) {
            statement.setString(1, name);
            statement.setString(2, phone);
            statement.setString(3, affiliation);
            statement.setString(4, about);
            statement.setLong(5, id);
            if (statement.executeUpdate() != 1) {
                throw new UserError("This account is no longer available. Please sign in again.");
            }
        } catch (SQLException exception) {
            throw Database.unavailable();
        }
    }

    private static Account read(ResultSet row) throws SQLException {
        return new Account(row.getLong("id"), row.getString("email"), row.getString("password_hash"),
                Role.valueOf(row.getString("role")), row.getBoolean("active"), row.getString("display_name"),
                row.getString("phone"), row.getString("affiliation"), row.getString("about"), row.getLong("credential_version"));
    }

    @Override public boolean changePassword(long id, long expectedVersion, String passwordHash) {
        return credentialTransaction(connection -> updatePassword(connection, id, expectedVersion, passwordHash));
    }

    @Override public boolean replaceRecoveryCodes(long id, long expectedVersion, List<byte[]> hashes) {
        return credentialTransaction(connection -> {
            var account = lockedAccount(connection, "id", id);
            if (account == null || !account.active() || account.credentialVersion() != expectedVersion) { return false; }
            deleteCodes(connection, id);
            try (var insert = connection.prepareStatement("INSERT INTO account_recovery_codes(user_id,code_hash) VALUES (?,?)")) {
                for (byte[] hash : hashes) {
                    insert.setLong(1, id);
                    insert.setBytes(2, hash);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            return true;
        });
    }

    @Override public boolean recoverPassword(String email, byte[] codeHash, String passwordHash) {
        return credentialTransaction(connection -> {
            var account = lockedAccount(connection, "email", email);
            boolean matched = false;
            byte[] dummy = new byte[32];
            try (var query = connection.prepareStatement("SELECT code_hash FROM account_recovery_codes WHERE user_id=?")) {
                query.setLong(1, account == null ? -1 : account.id());
                try (var rows = query.executeQuery()) {
                    boolean found = rows.next();
                    for (int i = 0; i < 6; i++) {
                        matched |= MessageDigest.isEqual(codeHash, found ? rows.getBytes(1) : dummy) && found;
                        if (found) { found = rows.next(); }
                    }
                }
            }
            return account != null && account.active() && matched
                    && updatePassword(connection, account.id(), account.credentialVersion(), passwordHash);
        });
    }

    private static Account lockedAccount(Connection connection, String key, Object value) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT * FROM users WHERE " + key + "=? FOR UPDATE")) {
            statement.setObject(1, value);
            try (var rows = statement.executeQuery()) { return rows.next() ? read(rows) : null; }
        }
    }

    private static boolean updatePassword(Connection connection, long id, long version, String hash) throws SQLException {
        try (var update = connection.prepareStatement("UPDATE users SET password_hash=?,credential_version=credential_version+1"
                + " WHERE id=? AND credential_version=? AND active=TRUE")) {
            update.setString(1, hash);
            update.setLong(2, id);
            update.setLong(3, version);
            if (update.executeUpdate() != 1) { return false; }
        }
        deleteCodes(connection, id);
        return true;
    }

    private static void deleteCodes(Connection connection, long id) throws SQLException {
        try (var delete = connection.prepareStatement("DELETE FROM account_recovery_codes WHERE user_id=?")) {
            delete.setLong(1, id);
            delete.executeUpdate();
        }
    }

    private interface CredentialWrite { boolean run(Connection connection) throws SQLException; }

    private boolean credentialTransaction(CredentialWrite write) {
        try (var connection = database.connect()) {
            connection.setAutoCommit(false);
            try {
                boolean result = write.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException failure) { throw Database.unavailable(); }
    }
}
