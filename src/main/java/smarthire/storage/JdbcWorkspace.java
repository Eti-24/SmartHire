package smarthire.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import smarthire.identity.*;
import smarthire.applications.Application;
import smarthire.workspace.WorkspaceRepository;
import smarthire.workspace.AdminData;
import smarthire.workspace.AdminActivity;
import smarthire.workspace.RecruitmentReport;
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
        @Override public List<Notice> notices(long userId, Instant now) {
            try (var query = connection.prepareStatement("SELECT n.*,CASE WHEN " + JdbcJobs.OPEN_AT
                    + " THEN j.id END AS matched_job_id FROM notifications n"
                    + " LEFT JOIN saved_search_notices s ON s.notice_id=n.id AND s.student_id=n.user_id"
                    + " LEFT JOIN jobs j ON j.id=s.job_id LEFT JOIN companies c ON c.user_id=j.company_id"
                    + " LEFT JOIN users u ON u.id=c.user_id"
                    + " WHERE n.user_id=? ORDER BY n.created_at DESC,n.id DESC")) {
                query.setObject(1, java.time.LocalDateTime.ofInstant(now.truncatedTo(java.time.temporal.ChronoUnit.MICROS), java.time.ZoneOffset.UTC));
                query.setLong(2, userId);
                var result = new ArrayList<Notice>();
                try (var rows = query.executeQuery()) {
                    while (rows.next()) { var read = rows.getTimestamp("read_at");
                        result.add(new Notice(UUID.fromString(rows.getString("id")), rows.getString("message"),
                                rows.getTimestamp("created_at").toInstant(), read == null ? null : read.toInstant(),
                                rows.getObject("matched_job_id", Long.class))); }
                } return List.copyOf(result);
            } catch (SQLException e) { throw Database.unavailable(); }
        }
        @Override public boolean markRead(long userId, UUID id) {
            // Read first so repeated marking succeeds independently of affected-row JDBC settings.
            if (count("SELECT COUNT(*) FROM notifications WHERE id=? AND user_id=?", id.toString(), userId) == 0) {
                return false;
            }
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
        @Override public Dashboard dashboard(long id, Role role, java.time.Instant now) {
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
                        + "JOIN users u ON u.id=c.user_id WHERE " + JdbcJobs.OPEN_AT,
                        java.time.LocalDateTime.ofInstant(now.truncatedTo(java.time.temporal.ChronoUnit.MICROS), java.time.ZoneOffset.UTC)));
                counts.put("Reviewed CV versions", count("SELECT COUNT(*) FROM cv_versions WHERE student_id=" + id));
            }
            var statuses = new LinkedHashMap<String, Long>();
            try (var query = connection.prepareStatement("SELECT a.status,COUNT(*)" + applications + " GROUP BY a.status"); var rows = query.executeQuery()) {
                for (var status : Application.Status.values()) { statuses.put(status.name(), 0L); }
                while (rows.next()) { statuses.put(rows.getString(1), rows.getLong(2)); }
            } catch (SQLException e) { throw Database.unavailable(); }
            return new Dashboard(counts, statuses);
        }

        @Override public RecruitmentReport recruitmentReport(Long companyId, RecruitmentReport.Filter filter) {
            var scope = new StringBuilder(" WHERE TRUE");
            var parameters = new ArrayList<Long>();
            addReportScope(scope, parameters, "j.company_id", companyId);
            addReportScope(scope, parameters, "j.id", filter.jobId());
            addReportScope(scope, parameters, "j.category_id", filter.categoryId());
            String cohort = """
                    SELECT COUNT(*) AS submissions, COALESCE(SUM(a.status='HIRED'),0) AS hired,
                           COUNT(first_shortlist.elapsed) AS shortlist_samples, AVG(first_shortlist.elapsed) AS shortlist_seconds,
                           COUNT(a.hired_at) AS hire_samples,
                           AVG(UNIX_TIMESTAMP(a.hired_at)-UNIX_TIMESTAMP(a.submitted_at)) AS hire_seconds
                    FROM applications a JOIN jobs j ON j.id=a.job_id
                    LEFT JOIN LATERAL (
                        SELECT TIMESTAMPDIFF(MICROSECOND,'1970-01-01',e.changed_utc)/1000000.0
                            - UNIX_TIMESTAMP(a.submitted_at) AS elapsed
                        FROM application_status_events e
                        WHERE e.application_id=a.id AND e.new_status='SHORTLISTED'
                        ORDER BY e.sequence_no LIMIT 1
                    ) first_shortlist ON TRUE
                    """ + scope + " AND UNIX_TIMESTAMP(a.submitted_at)>=? AND UNIX_TIMESTAMP(a.submitted_at)<?";
            String capacity = "SELECT COALESCE(SUM(j.vacancy_count),0),COALESCE(SUM(" + JdbcJobs.HIRED_COUNT + "),0) FROM jobs j" + scope;
            try (var applications = connection.prepareStatement(cohort); var jobs = connection.prepareStatement(capacity)) {
                for (int i = 0; i < parameters.size(); i++) {
                    applications.setLong(i + 1, parameters.get(i));
                    jobs.setLong(i + 1, parameters.get(i));
                }
                applications.setBigDecimal(parameters.size() + 1, epochSeconds(filter.from()));
                applications.setBigDecimal(parameters.size() + 2, epochSeconds(filter.until()));
                try (var results = applications.executeQuery(); var totals = jobs.executeQuery()) {
                    results.next();
                    totals.next();
                    return new RecruitmentReport(results.getLong("submissions"), results.getLong("hired"),
                            results.getLong("shortlist_samples"), results.getBigDecimal("shortlist_seconds"),
                            results.getLong("hire_samples"), results.getBigDecimal("hire_seconds"), totals.getLong(1), totals.getLong(2));
                }
            } catch (SQLException e) { throw Database.unavailable(); }
        }

        private static void addReportScope(StringBuilder sql, List<Long> parameters, String column, Long value) {
            if (value != null) {
                sql.append(" AND ").append(column).append("=?");
                parameters.add(value);
            }
        }

        private static BigDecimal epochSeconds(Instant value) {
            return BigDecimal.valueOf(value.getEpochSecond()).add(BigDecimal.valueOf(value.getNano(), 9));
        }
        private long count(String sql, Object... parameters) {
            try (var query = connection.prepareStatement(sql)) {
                for (int i = 0; i < parameters.length; i++) { query.setObject(i + 1, parameters[i]); }
                try (var row = query.executeQuery()) { row.next(); return row.getLong(1); }
            }
            catch (SQLException e) { throw Database.unavailable(); }
        }
        private void update(String sql, Object... values) {
            try (var query = connection.prepareStatement(sql)) {
                for (int i = 0; i < values.length; i++) { query.setObject(i + 1, values[i]); } query.executeUpdate();
            } catch (SQLException e) { throw Database.unavailable(); }
        }
    }
}
