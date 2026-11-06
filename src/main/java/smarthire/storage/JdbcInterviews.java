package smarthire.storage;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import smarthire.applications.Interview;

final class JdbcInterviews {
    private final Connection connection;

    JdbcInterviews(Connection connection) { this.connection = connection; }

    List<Interview> history(UUID applicationId) {
        return read("SELECT * FROM interview_events WHERE application_id=? ORDER BY revision DESC", List.of(applicationId.toString()));
    }

    List<Interview> current(Long studentId, Long companyId, Long jobId) {
        var sql = new StringBuilder("SELECT i.* FROM interview_events i JOIN applications a ON a.id=i.application_id"
                + " JOIN jobs j ON j.id=a.job_id WHERE NOT EXISTS (SELECT 1 FROM interview_events newer"
                + " WHERE newer.application_id=i.application_id AND newer.revision>i.revision)");
        var parameters = new ArrayList<Object>();
        if (studentId != null) { sql.append(" AND a.student_id=?"); parameters.add(studentId); }
        if (companyId != null) { sql.append(" AND j.company_id=?"); parameters.add(companyId); }
        if (jobId != null) { sql.append(" AND j.id=?"); parameters.add(jobId); }
        sql.append(" ORDER BY i.starts_utc,i.application_id");
        return read(sql.toString(), parameters);
    }

    private List<Interview> read(String sql, List<?> parameters) {
        var result = new ArrayList<Interview>();
        try (var query = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.size(); i++) { query.setObject(i + 1, parameters.get(i)); }
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    var slot = new Interview.Slot(time(rows, "starts_utc"), time(rows, "ends_utc"),
                            ZoneId.of(rows.getString("display_zone")), rows.getString("location"));
                    result.add(new Interview(UUID.fromString(rows.getString("application_id")), rows.getInt("revision"),
                            Interview.Status.valueOf(rows.getString("status")), slot, time(rows, "changed_utc"),
                            rows.getString("actor"), rows.getString("reason")));
                }
            }
        } catch (SQLException e) { throw Database.unavailable(); }
        return List.copyOf(result);
    }

    void add(Interview event, long studentId, long companyId) {
        var slot = event.slot();
        try (var query = connection.prepareStatement("INSERT INTO interview_events VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            query.setString(1, event.applicationId().toString());
            query.setInt(2, event.revision());
            query.setString(3, event.status().name());
            query.setObject(4, utc(slot.start()));
            query.setObject(5, utc(slot.end()));
            query.setString(6, slot.zone().getId());
            query.setString(7, slot.location());
            query.setObject(8, utc(event.changedAt()));
            query.setString(9, event.actor());
            query.setString(10, event.reason());
            query.executeUpdate();
            if (!event.actor().equals("STUDENT")) { notify(event, studentId); }
            if (!event.actor().equals("COMPANY")) { notify(event, companyId); }
        } catch (SQLException e) { throw Database.unavailable(); }
    }

    void cancelForAccount(long id, boolean company, Instant now) {
        for (Interview current : current(company ? null : id, company ? id : null, null)) {
            if (!current.canCancel(now)) { continue; }
            try (var query = connection.prepareStatement("SELECT a.student_id,j.company_id FROM applications a"
                    + " JOIN jobs j ON j.id=a.job_id WHERE a.id=?")) {
                query.setString(1, current.applicationId().toString());
                try (var row = query.executeQuery()) {
                    if (row.next()) { add(cancelled(current, now, "Account disabled"), row.getLong(1), row.getLong(2)); }
                }
            } catch (SQLException e) { throw Database.unavailable(); }
        }
    }

    static Interview cancelled(Interview current, Instant now, String reason) {
        return new Interview(current.applicationId(), current.revision() + 1, Interview.Status.CANCELLED,
                current.slot(), now, "SYSTEM", reason);
    }

    private void notify(Interview event, long recipient) throws SQLException {
        try (var active = connection.prepareStatement("SELECT active FROM users WHERE id=?")) {
            active.setLong(1, recipient);
            try (var row = active.executeQuery()) { if (!row.next() || !row.getBoolean(1)) { return; } }
        }
        UUID notice = JdbcNotifications.add(connection, recipient, "An interview was updated. Open Interviews for details.");
        try (var link = connection.prepareStatement("INSERT INTO interview_notices(notice_id,application_id) VALUES (?,?)")) {
            link.setString(1, notice.toString());
            link.setString(2, event.applicationId().toString());
            link.executeUpdate();
        }
    }

    private static Instant time(ResultSet row, String column) throws SQLException {
        return row.getObject(column, LocalDateTime.class).toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime utc(Instant time) { return LocalDateTime.ofInstant(time, ZoneOffset.UTC); }
}
