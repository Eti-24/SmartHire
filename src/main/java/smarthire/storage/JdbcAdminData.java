package smarthire.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import smarthire.documents.StoredDocument;
import smarthire.identity.UserError;
import smarthire.workspace.AdminData;
import static smarthire.workspace.AdminData.*;

/** Runs inside the workspace transaction, under the shared job catalog lock. */
final class JdbcAdminData implements AdminData.Store {
    private final Connection connection;

    JdbcAdminData(Connection connection) { this.connection = connection; }

    @Override public List<Entry> list(Kind kind) {
        return entries(kind, " ORDER BY 2,1", null);
    }

    private List<Entry> entries(Kind kind, String suffix, Object id) {
        String sql = switch (kind) {
            case STUDENT, COMPANY -> "SELECT u.id,u.display_name,u.email FROM users u WHERE u.role='" + kind.name() + "'";
            case JOB -> "SELECT j.id,j.title,u.display_name FROM jobs j JOIN users u ON u.id=j.company_id WHERE TRUE";
            case CV -> "SELECT v.id,d.original_name,CONCAT(u.display_name,' · ',v.reviewed_at) FROM cv_versions v "
                    + "JOIN managed_documents d ON d.id=v.document_id JOIN users u ON u.id=v.student_id WHERE TRUE";
        };
        var result = new ArrayList<Entry>();
        try (var query = prepare(sql + suffix, id); var rows = query.executeQuery()) {
            while (rows.next()) {
                result.add(new Entry(new Target(kind, rows.getString(1)), rows.getString(2), rows.getString(3)));
            }
            return List.copyOf(result);
        } catch (SQLException exception) { throw Database.unavailable(); }
    }

    private Entry lockTarget(Target target, Scope scope) {
        String column = switch (target.kind()) {
            case STUDENT, COMPANY -> "u.id";
            case JOB -> "j.id";
            case CV -> "v.id";
        };
        return entries(target.kind(), " AND " + column + "=? FOR UPDATE", scope.id()).stream().findFirst()
                .orElseThrow(() -> new UserError("This record no longer exists or cannot be managed here. Refresh the list."));
    }

    @Override public Impact impact(Target target) {
        var scope = Scope.of(target);
        return impact(lockTarget(target, scope), scope, originals(scope));
    }

    private Impact impact(Entry entry, Scope scope, List<Original> documents) {
        return new Impact(entry, count("jobs", scope.jobs(), scope), count("cv_versions", scope.versions(), scope),
                count("applications", scope.applications(), scope), documents.size(),
                scope.jobs() == null ? count("jobs j " + scope.closureJoin(), "j.status<>'CLOSED'", scope) : 0,
                count("saved_jobs", entry.target().kind() == Kind.STUDENT ? "student_id=?" : scope.jobChildren(), scope),
                count("interview_events", scope.applicationChildren(), scope), count("notifications", scope.interviewNotices(), scope),
                count("application_notes", scope.applicationChildren(), scope), count("interview_feedback", scope.applicationChildren(), scope),
                scope.jobs() == null ? additionalOpenVacancies(scope) : 0);
    }

    @Override public List<Original> export(Target target, Consumer<Table> writeTable) {
        var scope = Scope.of(target);
        lockTarget(target, scope);
        if (scope.account()) {
            writeTable.accept(table("account", "SELECT id,email,role,active,display_name,phone,affiliation,about,created_at FROM users WHERE id=?", scope.id()));
            exportTable(writeTable, "notifications", "user_id=?", scope);
        }
        if (target.kind() == Kind.COMPANY) { exportTable(writeTable, "companies", "user_id=?", scope); }
        if (target.kind() == Kind.STUDENT) {
            exportTable(writeTable, "saved_jobs", "student_id=?", scope);
            exportTable(writeTable, "saved_job_searches", "student_id=?", scope);
            exportTable(writeTable, "saved_search_notices", "student_id=?", scope);
        }
        exportTable(writeTable, "applications", scope.applications(), scope);
        exportTable(writeTable, "interview_events", scope.applicationChildren(), scope);
        exportTable(writeTable, "application_status_events", scope.applicationChildren(), scope);
        exportTable(writeTable, "interview_notices", scope.applicationChildren(), scope);
        exportTable(writeTable, "application_notes", scope.applicationChildren(), scope);
        exportTable(writeTable, "interview_feedback", scope.applicationChildren(), scope);
        exportTable(writeTable, "jobs", scope.jobs(), scope);
        exportTable(writeTable, "job_requirements", scope.jobChildren(), scope);
        exportTable(writeTable, "job_circulars", scope.jobChildren(), scope);
        exportTable(writeTable, "job_circular_versions", scope.jobChildren(), scope);
        exportTable(writeTable, "cv_versions", scope.versions(), scope);
        for (String table : CV_CHILDREN) { exportTable(writeTable, table, scope.cvChildren(), scope); }
        exportTable(writeTable, "cv_approvals", scope.cvChildren(), scope);
        exportTable(writeTable, "cv_current", scope.cvChildren(), scope);
        if (scope.documents() != null) { exportTable(writeTable, "managed_documents", scope.documents(), scope); }
        exportNotices(writeTable, "application_notices", scope.applicationNotices(), scope);
        exportNotices(writeTable, "interview_notification_messages", scope.interviewNotices(), scope);
        exportNotices(writeTable, "job_notices", scope.jobNotices(), scope);
        exportNotices(writeTable, "cv_notices", scope.cvNotices(), scope);
        return originals(scope);
    }

    private void exportTable(Consumer<Table> write, String name, String condition, Scope scope) {
        if (condition != null) { write.accept(table(name, "SELECT * FROM " + name + " WHERE " + condition, scope.id())); }
    }

    private void exportNotices(Consumer<Table> write, String name, String condition, Scope scope) {
        if (condition != null) { write.accept(table(name, "SELECT * FROM notifications WHERE " + condition, scope.id())); }
    }

    private Table table(String name, String sql, Object id) {
        try (var query = prepare(sql, id); var rows = query.executeQuery()) {
            var metadata = rows.getMetaData();
            var columns = new ArrayList<String>();
            for (int i = 1; i <= metadata.getColumnCount(); i++) { columns.add(metadata.getColumnLabel(i)); }
            var values = new ArrayList<List<String>>();
            while (rows.next()) {
                var row = new ArrayList<String>();
                for (int i = 1; i <= columns.size(); i++) { row.add(rows.getString(i)); }
                values.add(row);
            }
            return new Table(name, columns, values);
        } catch (SQLException exception) { throw Database.unavailable(); }
    }

    private static final List<String> CV_CHILDREN = List.of("cv_evidence_skills", "cv_evidence", "cv_skills", "cv_certifications");

    @Override public List<Original> erase(Impact confirmed) {
        var target = confirmed.entry().target();
        var scope = Scope.of(target);
        var entry = lockTarget(target, scope);
        var documents = originals(scope);
        if (!confirmed.equals(impact(entry, scope, documents))) {
            throw new UserError("The record or its erase impact changed. Review the updated details and confirm again.");
        }
        if (scope.jobs() == null) {
            update("UPDATE jobs j " + scope.closureJoin()
                    + " SET j.status='CLOSED',j.decision='Closed after administrative erasure of application records' WHERE j.status<>'CLOSED'", scope.id());
        }
        delete("notifications", scope.applicationNotices(), scope);
        delete("notifications", scope.interviewNotices(), scope);
        delete("notifications", scope.jobNotices(), scope);
        delete("notifications", scope.savedSearchNotices(), scope);
        delete("notifications", scope.cvNotices(), scope);
        delete("applications", scope.applications(), scope);
        delete("job_circulars", scope.jobChildren(), scope);
        delete("job_circular_versions", scope.jobChildren(), scope);
        delete("job_requirements", scope.jobChildren(), scope);
        delete("jobs", scope.jobs(), scope);
        delete("cv_current", scope.cvChildren(), scope);
        for (String table : CV_CHILDREN) { delete(table, scope.cvChildren(), scope); }
        delete("cv_versions", scope.versions(), scope);
        if (scope.account()) {
            delete(target.kind() == Kind.STUDENT ? "students" : "companies", "user_id=?", scope);
            delete("users", "id=?", scope);
        }

        var unreferenced = new ArrayList<Original>();
        for (var document : documents) {
            int removed = update("DELETE FROM managed_documents WHERE id=? "
                    + "AND NOT EXISTS (SELECT 1 FROM cv_versions WHERE document_id=managed_documents.id) "
                    + "AND NOT EXISTS (SELECT 1 FROM job_circular_versions WHERE document_id=managed_documents.id) "
                    + "AND NOT EXISTS (SELECT 1 FROM job_circulars WHERE document_id=managed_documents.id)", document.id().toString());
            if (removed == 1) { unreferenced.add(document); }
        }
        return List.copyOf(unreferenced);
    }

    private void delete(String table, String condition, Scope scope) {
        if (condition != null) { update("DELETE FROM " + table + " WHERE " + condition, scope.id()); }
    }

    private List<Original> originals(Scope scope) {
        if (scope.documents() == null) { return List.of(); }
        var result = new ArrayList<Original>();
        try (var query = prepare("SELECT id,format FROM managed_documents WHERE " + scope.documents(), scope.id());
                var rows = query.executeQuery()) {
            while (rows.next()) {
                result.add(new Original(UUID.fromString(rows.getString(1)), StoredDocument.Format.valueOf(rows.getString(2))));
            }
            return List.copyOf(result);
        } catch (SQLException exception) { throw Database.unavailable(); }
    }

    private long count(String table, String condition, Scope scope) {
        if (condition == null) { return 0; }
        try (var query = prepare("SELECT COUNT(*) FROM " + table + " WHERE " + condition, scope.id());
                var row = query.executeQuery()) { row.next(); return row.getLong(1); }
        catch (SQLException exception) { throw Database.unavailable(); }
    }

    private long additionalOpenVacancies(Scope scope) {
        String sql = "SELECT COALESCE(SUM(affected.hires),0) FROM jobs j " + scope.affectedJoin()
                + " JOIN users u ON u.id=j.company_id JOIN companies c ON c.user_id=j.company_id"
                + " WHERE j.status='APPROVED' AND u.active=TRUE AND c.verified=TRUE AND NOT " + JdbcJobs.FILLED_JOB
                + " AND affected.removed<(SELECT COUNT(*) FROM applications a WHERE a.job_id=j.id)";
        try (var query = prepare(sql, scope.id()); var row = query.executeQuery()) {
            row.next();
            return row.getLong(1);
        } catch (SQLException exception) { throw Database.unavailable(); }
    }

    private int update(String sql, Object id) {
        try (var query = prepare(sql, id)) { return query.executeUpdate(); }
        catch (SQLException exception) { throw Database.unavailable(); }
    }

    private PreparedStatement prepare(String sql, Object id) throws SQLException {
        var query = connection.prepareStatement(sql);
        if (id != null) { query.setObject(1, id); }
        return query;
    }

    private record Scope(Object id, boolean account, String jobs, String versions, String applications) {
        static Scope of(Target target) {
            try {
                return switch (target.kind()) {
                    case STUDENT -> new Scope(Long.parseLong(target.id()), true, null, "student_id=?", "student_id=?");
                    case COMPANY -> new Scope(Long.parseLong(target.id()), true, "company_id=?", null,
                            "job_id IN (SELECT id FROM jobs WHERE company_id=?)");
                    case JOB -> new Scope(Long.parseLong(target.id()), false, "id=?", null, "job_id=?");
                    case CV -> new Scope(UUID.fromString(target.id()).toString(), false, null, "id=?", "cv_version_id=?");
                };
            } catch (IllegalArgumentException exception) { throw new UserError("Invalid record identifier."); }
        }

        String jobChildren() { return jobs == null ? null : "job_id IN (SELECT id FROM jobs WHERE " + jobs + ")"; }
        String cvChildren() { return versions == null ? null : "version_id IN (SELECT id FROM cv_versions WHERE " + versions + ")"; }
        String applicationChildren() { return "application_id IN (SELECT id FROM applications WHERE " + applications + ")"; }
        String interviewNotices() { return "id IN (SELECT notice_id FROM interview_notices WHERE " + applicationChildren() + ")"; }
        String savedSearchNotices() {
            return jobs == null ? null : "id IN (SELECT notice_id FROM saved_search_notices WHERE " + jobChildren() + ")";
        }
        String documents() {
            return jobs == null ? "id IN (SELECT document_id FROM cv_versions WHERE " + versions + ")"
                    : "id IN (SELECT document_id FROM job_circular_versions WHERE " + jobChildren() + ")";
        }
        // Notices are linked by their generated message identifiers in the existing schema.
        String applicationNotices() {
            return "EXISTS (SELECT 1 FROM applications a WHERE " + applications
                    + " AND notifications.message LIKE CONCAT('Application % for job #',a.job_id,'. Application: ',a.id))";
        }
        String jobNotices() {
            return jobs == null ? null : "EXISTS (SELECT 1 FROM jobs j WHERE " + jobs
                    + " AND j.company_id=notifications.user_id AND notifications.message LIKE CONCAT('Job #',j.id,' %'))";
        }
        String cvNotices() {
            return versions == null ? null : "EXISTS (SELECT 1 FROM cv_versions v WHERE " + versions
                    + " AND v.student_id=notifications.user_id AND notifications.message LIKE CONCAT('CV ',v.id,' %'))";
        }
        String affectedJoin() {
            return "JOIN (SELECT job_id,COUNT(*) removed,SUM(status='HIRED') hires FROM applications WHERE " + applications
                    + " GROUP BY job_id) affected ON affected.job_id=j.id";
        }
        String closureJoin() {
            return affectedJoin() + " AND ((affected.hires>0 AND " + JdbcJobs.FILLED_JOB + ")"
                    + " OR affected.removed=(SELECT COUNT(*) FROM applications a WHERE a.job_id=j.id))";
        }
    }
}
