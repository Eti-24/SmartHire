package smarthire.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import smarthire.workspace.AdminActivity;

final class JdbcAdminActivity {
    private static final int PAGE_SIZE = 50;

    private JdbcAdminActivity() { }

    static void add(Connection connection, long adminId, AdminActivity.Change change) {
        String sql = "INSERT INTO admin_activity(admin_id,action,target_kind,target_id,outcome,record_count,operation_id) VALUES (?,?,?,?,?,?,?)";
        try (var query = connection.prepareStatement(sql)) {
            query.setLong(1, adminId);
            query.setString(2, change.action().name());
            query.setString(3, change.targetKind());
            query.setString(4, change.targetId());
            query.setString(5, change.outcome());
            query.setObject(6, change.count());
            query.setString(7, change.operationId() == null ? null : change.operationId().toString());
            query.executeUpdate();
        } catch (SQLException e) { throw Database.unavailable(); }
    }

    static AdminActivity.Page page(Connection connection, AdminActivity.Filter filter) {
        String sql = "SELECT e.*,EXISTS (SELECT 1 FROM admin_activity x WHERE x.operation_id=e.operation_id AND x.id>e.id) AS has_outcome"
                + " FROM admin_activity e WHERE TRUE";
        var parameters = new ArrayList<Object>();
        if (filter.action() != null) { sql += " AND e.action=?"; parameters.add(filter.action().name()); }
        if (filter.targetId() != null) { sql += " AND e.target_id=?"; parameters.add(filter.targetId()); }
        if (filter.from() != null) { sql += " AND e.event_at>=?"; parameters.add(Timestamp.from(filter.from())); }
        if (filter.until() != null) { sql += " AND e.event_at<?"; parameters.add(Timestamp.from(filter.until())); }
        if (filter.beforeId() != null) { sql += " AND e.id<?"; parameters.add(filter.beforeId()); }
        sql += " ORDER BY e.id DESC LIMIT " + (PAGE_SIZE + 1);
        try (var query = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.size(); i++) { query.setObject(i + 1, parameters.get(i)); }
            var entries = new ArrayList<AdminActivity.Entry>();
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    String operation = rows.getString("operation_id"), outcome = rows.getString("outcome");
                    if (outcome.equals("STARTED") && !rows.getBoolean("has_outcome")) { outcome = "OUTCOME UNRESOLVED"; }
                    var change = new AdminActivity.Change(AdminActivity.Action.valueOf(rows.getString("action")),
                            rows.getString("target_kind"), rows.getString("target_id"), outcome,
                            rows.getObject("record_count", Long.class), operation == null ? null : UUID.fromString(operation));
                    entries.add(new AdminActivity.Entry(rows.getLong("id"), rows.getTimestamp("event_at").toInstant(), rows.getLong("admin_id"), change));
                }
            }
            Long next = null;
            if (entries.size() > PAGE_SIZE) {
                entries.removeLast();
                next = entries.getLast().id();
            }
            return new AdminActivity.Page(List.copyOf(entries), next);
        } catch (SQLException e) { throw Database.unavailable(); }
    }
}
