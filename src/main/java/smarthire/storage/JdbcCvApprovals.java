package smarthire.storage;

import java.sql.*;
import java.util.*;
import java.util.function.Function;
import smarthire.cv.*;
import smarthire.identity.*;
import static smarthire.cv.CvApprovalRepository.*;

public final class JdbcCvApprovals implements CvApprovalRepository {
    private final Database database;
    public JdbcCvApprovals(Database database) { this.database = database; }
    @Override public <T> T transaction(Function<Store, T> work) {
        try (var connection = database.connect()) {
            connection.setAutoCommit(false);
            try {
                try (var query = connection.prepareStatement("SELECT id FROM job_catalog_lock WHERE id=1 FOR UPDATE"); var row = query.executeQuery()) { row.next(); }
                T result = work.apply(new Session(connection)); connection.commit(); return result;
            } catch (RuntimeException | SQLException e) { connection.rollback(); throw e; }
        } catch (SQLException e) { throw Database.unavailable(); }
    }
    static CvApproval approval(ResultSet row) throws SQLException {
        if (row.getString("status") == null) { return CvApproval.pending(); }
        var reviewer = (Number) row.getObject("reviewer_id"); var at = row.getTimestamp("decided_at");
        return new CvApproval(CvApproval.Status.valueOf(row.getString("status")), row.getString("reason"), reviewer == null ? null : reviewer.longValue(), at == null ? null : at.toInstant());
    }
    private static final class Session implements Store {
        private final Connection connection;
        Session(Connection connection) { this.connection = connection; }
        @Override public void requireActive(long id, Role role) {
            try (var query = connection.prepareStatement("SELECT active,role FROM users WHERE id=? FOR UPDATE")) {
                query.setLong(1, id);
                try (var row = query.executeQuery()) { if (!row.next() || !row.getBoolean(1) || !row.getString(2).equals(role.name())) { throw new UserError("An active " + role.name().toLowerCase(Locale.ROOT) + " account is required."); } }
            } catch (SQLException e) { throw Database.unavailable(); }
        }
        private static final String SELECT = "SELECT v.id,v.student_id,v.reviewed_at,d.original_name,u.display_name,u.email,u.active,a.status,a.reason,a.reviewer_id,a.decided_at FROM cv_versions v JOIN managed_documents d ON d.id=v.document_id JOIN users u ON u.id=v.student_id LEFT JOIN cv_approvals a ON a.version_id=v.id ";
        @Override public List<Submission> list(CvApproval.Status status) {
            try (var query = connection.prepareStatement(SELECT + "JOIN cv_current c ON c.version_id=v.id AND c.student_id=v.student_id "
                    + (status == null ? "" : "WHERE COALESCE(a.status,'PENDING')=? ") + "ORDER BY v.reviewed_at,v.id")) {
                if (status != null) { query.setString(1, status.name()); }
                try (var rows = query.executeQuery()) { var result = new ArrayList<Submission>(); while (rows.next()) { result.add(submission(rows)); } return List.copyOf(result); }
            } catch (SQLException e) { throw Database.unavailable(); }
        }
        private Submission submission(ResultSet row) throws SQLException {
            return new Submission(UUID.fromString(row.getString("id")), row.getLong("student_id"), row.getString("display_name"), row.getString("email"), row.getBoolean("active"), row.getString("original_name"), row.getTimestamp("reviewed_at").toInstant(), approval(row));
        }
        @Override public Optional<Review> find(UUID version) {
            try (var query = connection.prepareStatement(SELECT + "WHERE v.id=?")) {
                query.setString(1, version.toString());
                try (var row = query.executeQuery()) {
                    if (!row.next()) { return Optional.empty(); }
                    var summary = submission(row); var cv = new JdbcCvs.Session(connection, summary.studentId()).find(version).orElseThrow();
                    boolean current;
                    try (var pointer = connection.prepareStatement("SELECT version_id FROM cv_current WHERE student_id=?")) {
                        pointer.setLong(1, summary.studentId());
                        try (var pointerRow = pointer.executeQuery()) { current = pointerRow.next() && version.toString().equals(pointerRow.getString(1)); }
                    }
                    return Optional.of(new Review(summary, cv, current));
                }
            } catch (SQLException e) { throw Database.unavailable(); }
        }
        @Override public UUID current(long studentId) {
            try (var query = connection.prepareStatement("SELECT version_id FROM cv_current WHERE student_id=? FOR UPDATE")) {
                query.setLong(1, studentId); try (var row = query.executeQuery()) { return row.next() ? UUID.fromString(row.getString(1)) : null; }
            } catch (SQLException e) { throw Database.unavailable(); }
        }
        @Override public void decide(UUID version, CvApproval a) {
            try (var query = connection.prepareStatement("INSERT INTO cv_approvals(version_id,status,reason,reviewer_id,decided_at) VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE status=?,reason=?,reviewer_id=?,decided_at=?")) {
                query.setString(1, version.toString());
                for (int start : new int[]{2, 6}) { query.setString(start, a.status().name()); query.setString(start + 1, a.reason()); query.setObject(start + 2, a.reviewerId()); query.setTimestamp(start + 3, Timestamp.from(a.decidedAt())); }
                query.executeUpdate();
                var review = find(version).orElseThrow();
                JdbcNotifications.add(connection, review.cv().studentId(), "CV " + version + " " + a.status().name().toLowerCase(Locale.ROOT) + (a.reason().isBlank() ? "." : ": " + a.reason()));
            } catch (SQLException e) { throw Database.unavailable(); }
        }
    }
}
