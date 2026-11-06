package smarthire.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/** Event writes use the source transaction, never a second connection. */
final class JdbcNotifications {
    private JdbcNotifications() { }

    static UUID add(Connection connection, long userId, String message) {
        if (message.codePointCount(0, message.length()) > 1000) { message = message.substring(0, message.offsetByCodePoints(0, 997)) + "..."; }
        UUID id = UUID.randomUUID();
        try (var query = connection.prepareStatement("INSERT INTO notifications(id,user_id,message) VALUES (?,?,?)")) {
            query.setString(1, id.toString()); query.setLong(2, userId);
            query.setString(3, message); query.executeUpdate(); return id;
        } catch (SQLException e) { throw Database.unavailable(); }
    }
}
