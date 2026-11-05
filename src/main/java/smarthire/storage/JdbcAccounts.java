package smarthire.storage;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
                row.getString("phone"), row.getString("affiliation"), row.getString("about"));
    }
}
