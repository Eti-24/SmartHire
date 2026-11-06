package smarthire.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Function;
import smarthire.identity.*;
import smarthire.applications.Application;
import smarthire.workspace.WorkspaceRepository;
import smarthire.workspace.AdminData;
import smarthire.workspace.AdminActivity;
import static smarthire.workspace.WorkspaceRepository.*;

public final class JdbcWorkspace implements WorkspaceRepository {
    private final Database database;
    public JdbcWorkspace(Database database) { this.database = database; }
    @Override public <T> T transaction(Function<Store, T> work) {
        try (var connection = database.connect()) {
            connection.setAutoCommit(false);
            try {
                // Same lock order as publication/applications, including account disablement.
                try (var query = connection.prepareStatement("SELECT id FROM job_catalog_lock WHERE id=1 FOR UPDATE"); var row = query.executeQuery()) { row.next(); }
                T result = work.apply(new Session(connection)); connection.commit(); return result;
            } catch (RuntimeException | SQLException e) { connection.rollback(); throw e; }
        } catch (SQLException e) { throw Database.unavailable(); }
    }
    private static final class Session implements Store {
        private final Connection connection;
        Session(Connection connection) { this.connection = connection; }
        @Override public void requireActive(long id, Role role) {
            try (var query = connection.prepareStatement("SELECT active,role FROM users WHERE id=? FOR UPDATE")) {
                query.setLong(1, id);
                try (var row = query.executeQuery()) {
                    if (!row.next() || !row.getBoolean(1) || !row.getString(2).equals(role.name())) { throw new UserError("An active account is required. Sign in again."); }
                }
            } catch (SQLException e) { throw Database.unavailable(); }
        }
        @Override public List<Notice> notices(long userId) {
            try (var query = connection.prepareStatement("SELECT * FROM notifications WHERE user_id=? ORDER BY created_at DESC,id DESC")) {
                query.setLong(1, userId); var result = new ArrayList<Notice>();
                try (var rows = query.executeQuery()) {
                    while (rows.next()) { var read = rows.getTimestamp("read_at");
                        result.add(new Notice(UUID.fromString(rows.getString("id")), rows.getString("message"), rows.getTimestamp("created_at").toInstant(), read == null ? null : read.toInstant())); }
                } return List.copyOf(result);
            } catch (SQLException e) { throw Database.unavailable(); }
        }
        @Override public boolean markRead(long userId, UUID id) {
            // Read first so repeated marking succeeds independently of affected-row JDBC settings.
            if (notices(userId).stream().noneMatch(n -> n.id().equals(id))) { return false; }
            update("UPDATE notifications SET read_at=COALESCE(read_at,CURRENT_TIMESTAMP(6)) WHERE id=? AND user_id=?", id.toString(), userId); return true;
        }
        @Override public List<AccountSummary> accounts() {
            try (var query = connection.prepareStatement("SELECT id,display_name,email,role,active FROM users ORDER BY id"); var rows = query.executeQuery()) {
                var result = new ArrayList<AccountSummary>();
                while (rows.next()) { result.add(new AccountSummary(rows.getLong(1), rows.getString(2), rows.getString(3), Role.valueOf(rows.getString(4)), rows.getBoolean(5))); }
                return List.copyOf(result);
            } catch (SQLException e) { throw Database.unavailable(); }
        }
        @Override public void setActive(long id, boolean active) {
            update("UPDATE users SET active=? WHERE id=?", active, id);
            if (!active) {
                var account = accounts().stream().filter(a -> a.id() == id).findFirst().orElseThrow();
                new JdbcInterviews(connection).cancelForAccount(id, account.role() == Role.COMPANY, java.time.Instant.now());
            }
        }
        @Override public AdminData.Store adminData() { return new JdbcAdminData(connection); }
        @Override public void audit(long adminId, AdminActivity.Change change) { JdbcAdminActivity.add(connection, adminId, change); }
        @Override public AdminActivity.Page activity(AdminActivity.Filter filter) { return JdbcAdminActivity.page(connection, filter); }
        @Override public List<ApplicationSummary> monitor(Filter f) {
            StringBuilder sql = new StringBuilder("SELECT a.*,j.title,j.company_id,u.display_name company FROM applications a JOIN jobs j ON j.id=a.job_id JOIN users u ON u.id=j.company_id WHERE 1=1");
            var values = new ArrayList<Object>();
            if (f.jobId() != null) { sql.append(" AND a.job_id=?"); values.add(f.jobId()); }
            if (f.companyId() != null) { sql.append(" AND j.company_id=?"); values.add(f.companyId()); }
            if (f.studentId() != null) { sql.append(" AND a.student_id=?"); values.add(f.studentId()); }
            if (f.status() != null) { sql.append(" AND a.status=?"); values.add(f.status().name()); }
            sql.append(" ORDER BY a.submitted_at DESC,a.id");
            try (var query = connection.prepareStatement(sql.toString())) {
                for (int i = 0; i < values.size(); i++) { query.setObject(i + 1, values.get(i)); }
                try (var rows = query.executeQuery()) {
                    var result = new ArrayList<ApplicationSummary>();
                    while (rows.next()) { result.add(new ApplicationSummary(UUID.fromString(rows.getString("id")), rows.getLong("job_id"), rows.getString("title"), rows.getLong("company_id"), rows.getString("company"),
                            rows.getLong("student_id"), rows.getString("student_name"), rows.getString("student_email"), Application.Status.valueOf(rows.getString("status")), rows.getTimestamp("submitted_at").toInstant(),
                            rows.getDouble("original_score"), rows.getString("original_policy"), rows.getString("original_explanation"))); }
                    return List.copyOf(result);
                }
            } catch (SQLException e) { throw Database.unavailable(); }
        }
        @Override public Dashboard dashboard(long id, Role role) {
            var counts = new LinkedHashMap<String, Long>();
            String appScope = switch (role) { case ADMIN -> ""; case STUDENT -> " WHERE a.student_id=" + id; case COMPANY -> " WHERE j.company_id=" + id; };
            String applications = " FROM applications a JOIN jobs j ON j.id=a.job_id" + appScope;
            counts.put(role == Role.COMPANY ? "Applicants" : "Applications", count("SELECT COUNT(*)" + applications));
            counts.put("Unread notifications", count("SELECT COUNT(*) FROM notifications WHERE read_at IS NULL AND user_id=" + id));
            if (role == Role.ADMIN) {
                counts.put("Active accounts", count("SELECT COUNT(*) FROM users WHERE active=TRUE"));
                counts.put("Disabled accounts", count("SELECT COUNT(*) FROM users WHERE active=FALSE"));
                counts.put("Unverified companies", count("SELECT COUNT(*) FROM companies c JOIN users u ON u.id=c.user_id WHERE c.verified=FALSE AND u.active=TRUE"));
                counts.put("Pending jobs", count("SELECT COUNT(*) FROM jobs WHERE status='PENDING'"));
                counts.put("CVs awaiting approval", count("SELECT COUNT(*) FROM cv_current c JOIN users u ON u.id=c.student_id LEFT JOIN cv_approvals a ON a.version_id=c.version_id WHERE u.active=TRUE AND COALESCE(a.status,'PENDING')='PENDING'"));
            } else if (role == Role.COMPANY) {
                counts.put("My jobs", count("SELECT COUNT(*) FROM jobs WHERE company_id=" + id));
                counts.put("Approved jobs", count("SELECT COUNT(*) FROM jobs WHERE status='APPROVED' AND company_id=" + id));
                counts.put("Pending jobs", count("SELECT COUNT(*) FROM jobs WHERE status='PENDING' AND company_id=" + id));
            } else {
                counts.put("Available jobs", count("SELECT COUNT(*) FROM jobs j JOIN companies c ON c.user_id=j.company_id "
                        + "JOIN users u ON u.id=c.user_id WHERE j.status='APPROVED' AND c.verified=TRUE AND u.active=TRUE AND NOT "
                        + JdbcJobs.FILLED_JOB));
                counts.put("Reviewed CV versions", count("SELECT COUNT(*) FROM cv_versions WHERE student_id=" + id));
            }
            var statuses = new LinkedHashMap<String, Long>();
            try (var query = connection.prepareStatement("SELECT a.status,COUNT(*)" + applications + " GROUP BY a.status"); var rows = query.executeQuery()) {
                for (var status : Application.Status.values()) { statuses.put(status.name(), 0L); }
                while (rows.next()) { statuses.put(rows.getString(1), rows.getLong(2)); }
            } catch (SQLException e) { throw Database.unavailable(); }
            return new Dashboard(counts, statuses);
        }
        private long count(String sql) {
            try (var query = connection.prepareStatement(sql); var row = query.executeQuery()) { row.next(); return row.getLong(1); }
            catch (SQLException e) { throw Database.unavailable(); }
        }
        private void update(String sql, Object... values) {
            try (var query = connection.prepareStatement(sql)) {
                for (int i = 0; i < values.length; i++) { query.setObject(i + 1, values[i]); } query.executeUpdate();
            } catch (SQLException e) { throw Database.unavailable(); }
        }
    }
}
