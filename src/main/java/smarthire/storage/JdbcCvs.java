package smarthire.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import smarthire.cv.CvProfile;
import smarthire.cv.CvRepository;
import smarthire.documents.StoredDocument;
import smarthire.identity.UserError;
import smarthire.jobs.Job.Degree;
import smarthire.skills.Skill;
import smarthire.skills.SkillVocabulary;
import static smarthire.cv.CvProfile.*;

public final class JdbcCvs implements CvRepository {
    private final Database database;
    public JdbcCvs(Database database) { this.database = database; }

    @Override public <T> T transaction(long studentId, Function<Store, T> work) {
        try (var connection = database.connect()) {
            connection.setAutoCommit(false);
            try {
                // Serialize confirmations and recheck disabled accounts under the same lock.
                try (var lock = connection.prepareStatement("SELECT u.active,u.role FROM users u JOIN students s ON s.user_id=u.id WHERE u.id=? FOR UPDATE")) {
                    lock.setLong(1, studentId);
                    try (var row = lock.executeQuery()) {
                        if (!row.next() || !row.getBoolean(1) || !row.getString(2).equals("STUDENT")) {
                            throw new UserError("An active student account is required for CV review.");
                        }
                    }
                }
                T result = work.apply(new Session(connection, studentId));
                connection.commit();
                return result;
            } catch (RuntimeException | SQLException exception) { connection.rollback(); throw exception; }
        } catch (SQLException exception) { throw Database.unavailable(); }
    }

    static final class Session implements Store {
        private final Connection connection;
        private final long studentId;
        Session(Connection connection, long studentId) { this.connection = connection; this.studentId = studentId; }

        @Override public Optional<CvProfile> current() {
            var ids = strings("SELECT version_id FROM cv_current WHERE student_id=?", studentId);
            return ids.isEmpty() ? Optional.empty() : find(UUID.fromString(ids.getFirst()));
        }

        @Override public smarthire.cv.CvApproval approval(UUID version) {
            try (var query = connection.prepareStatement("SELECT a.status,a.reason,a.reviewer_id,a.decided_at FROM cv_versions v LEFT JOIN cv_approvals a ON a.version_id=v.id WHERE v.student_id=? AND v.id=?")) {
                query.setLong(1, studentId); query.setString(2, version.toString());
                try (var row = query.executeQuery()) {
                    if (!row.next()) { throw new UserError("This CV version is not available to your account."); }
                    return JdbcCvApprovals.approval(row);
                }
            } catch (SQLException e) { throw Database.unavailable(); }
        }

        @Override public Optional<CvProfile> find(UUID version) {
            try (var statement = connection.prepareStatement("SELECT v.*,d.original_name,d.extracted_text FROM cv_versions v JOIN managed_documents d ON d.id=v.document_id WHERE v.id=? AND v.student_id=?")) {
                statement.setString(1, version.toString()); statement.setLong(2, studentId);
                try (var row = statement.executeQuery()) {
                    if (!row.next()) { return Optional.empty(); }
                    String degree = row.getString("degree");
                    var qualifications = new Qualifications(strings("SELECT skill_id FROM cv_skills WHERE version_id=? ORDER BY ordinal", version),
                            degree == null ? null : Degree.valueOf(degree), row.getString("degree_field"), projects(version), experience(version),
                            strings("SELECT name FROM cv_certifications WHERE version_id=? ORDER BY ordinal", version));
                    return Optional.of(new CvProfile(version, studentId, UUID.fromString(row.getString("document_id")),
                            row.getString("original_name"), row.getString("extracted_text"), row.getTimestamp("reviewed_at").toInstant(), qualifications));
                }
            } catch (SQLException exception) { throw Database.unavailable(); }
        }

        @Override public List<Version> history() {
            try (var query = connection.prepareStatement("SELECT v.id,d.original_name,v.reviewed_at FROM cv_versions v JOIN managed_documents d ON d.id=v.document_id WHERE v.student_id=? ORDER BY v.reviewed_at DESC,v.id")) {
                query.setLong(1, studentId);
                try (var rows = query.executeQuery()) {
                    var result = new ArrayList<Version>();
                    while (rows.next()) { result.add(new Version(UUID.fromString(rows.getString(1)), rows.getString(2), rows.getTimestamp(3).toInstant())); }
                    return List.copyOf(result);
                }
            } catch (SQLException exception) { throw Database.unavailable(); }
        }

        @Override public SkillVocabulary vocabulary() {
            var skills = new ArrayList<Skill>();
            try (var query = connection.prepareStatement("SELECT id,name,type FROM skills ORDER BY id"); var rows = query.executeQuery()) {
                while (rows.next()) {
                    String id = rows.getString(1);
                    skills.add(new Skill(id, rows.getString(2), Skill.Type.valueOf(rows.getString(3)),
                            new HashSet<>(strings("SELECT phrase FROM skill_aliases WHERE skill_id=?", id))));
                }
                return new SkillVocabulary(skills);
            } catch (SQLException exception) { throw Database.unavailable(); }
        }

        @Override public void save(CvProfile profile, StoredDocument newDocument) {
            if (profile.studentId() != studentId) { throw new UserError("You can only save your own CV."); }
            try {
                if (newDocument != null) { JdbcDocumentReferences.insert(connection, newDocument); }
                Qualifications q = profile.qualifications();
                update("INSERT INTO cv_versions(id,student_id,document_id,reviewed_at,degree,degree_field) VALUES (?,?,?,?,?,?)",
                        profile.id(), studentId, profile.documentId(), Timestamp.from(profile.reviewedAt()), q.degree() == null ? null : q.degree().name(), q.field());
                for (int i = 0; i < q.skills().size(); i++) {
                    update("INSERT INTO cv_skills(version_id,ordinal,skill_id) VALUES (?,?,?)", profile.id(), i, q.skills().get(i));
                }
                for (int i = 0; i < q.projects().size(); i++) {
                    Project project = q.projects().get(i);
                    evidence(profile.id(), "PROJECT", i, project.evidence(), null, null, false, project.skills());
                }
                for (int i = 0; i < q.experience().size(); i++) {
                    Experience entry = q.experience().get(i);
                    evidence(profile.id(), "EXPERIENCE", i, entry.evidence(), entry.start(), entry.end(), entry.ongoing(), entry.skills());
                }
                for (int i = 0; i < q.certifications().size(); i++) {
                    update("INSERT INTO cv_certifications(version_id,ordinal,name) VALUES (?,?,?)", profile.id(), i, q.certifications().get(i));
                }
                update("INSERT INTO cv_current(student_id,version_id) VALUES (?,?) ON DUPLICATE KEY UPDATE version_id=?", studentId, profile.id(), profile.id());
                update("INSERT INTO cv_approvals(version_id) VALUES (?)", profile.id());
            } catch (SQLException exception) { throw Database.unavailable(); }
        }

        private void evidence(UUID version, String kind, int ordinal, String text, YearMonth start, YearMonth end, boolean ongoing, List<String> skills) throws SQLException {
            update("INSERT INTO cv_evidence(version_id,kind,ordinal,evidence,start_month,end_month,ongoing) VALUES (?,?,?,?,?,?,?)",
                    version, kind, ordinal, text, start, end, ongoing);
            for (int i = 0; i < skills.size(); i++) {
                update("INSERT INTO cv_evidence_skills(version_id,kind,evidence_ordinal,ordinal,skill_id) VALUES (?,?,?,?,?)", version, kind, ordinal, i, skills.get(i));
            }
        }

        private List<Project> projects(UUID version) throws SQLException {
            var result = new ArrayList<Project>();
            try (var query = connection.prepareStatement("SELECT ordinal,evidence FROM cv_evidence WHERE version_id=? AND kind='PROJECT' ORDER BY ordinal")) {
                query.setString(1, version.toString());
                try (var rows = query.executeQuery()) {
                    while (rows.next()) { result.add(new Project(rows.getString(2), evidenceSkills(version, "PROJECT", rows.getInt(1)))); }
                }
            }
            return List.copyOf(result);
        }

        private List<Experience> experience(UUID version) throws SQLException {
            var result = new ArrayList<Experience>();
            try (var query = connection.prepareStatement("SELECT * FROM cv_evidence WHERE version_id=? AND kind='EXPERIENCE' ORDER BY ordinal")) {
                query.setString(1, version.toString());
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        result.add(new Experience(rows.getString("evidence"), YearMonth.parse(rows.getString("start_month")),
                                YearMonth.parse(rows.getString("end_month")), rows.getBoolean("ongoing"), evidenceSkills(version, "EXPERIENCE", rows.getInt("ordinal"))));
                    }
                }
            }
            return List.copyOf(result);
        }

        private List<String> evidenceSkills(UUID version, String kind, int ordinal) {
            return strings("SELECT skill_id FROM cv_evidence_skills WHERE version_id=? AND kind=? AND evidence_ordinal=? ORDER BY ordinal", version, kind, ordinal);
        }

        private List<String> strings(String sql, Object... values) {
            try (var query = connection.prepareStatement(sql)) {
                for (int i = 0; i < values.length; i++) { query.setObject(i + 1, jdbc(values[i])); }
                try (var rows = query.executeQuery()) {
                    var result = new ArrayList<String>();
                    while (rows.next()) { result.add(rows.getString(1)); }
                    return List.copyOf(result);
                }
            } catch (SQLException exception) { throw Database.unavailable(); }
        }

        private void update(String sql, Object... values) throws SQLException {
            try (var statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < values.length; i++) { statement.setObject(i + 1, jdbc(values[i])); }
                statement.executeUpdate();
            }
        }

        private static Object jdbc(Object value) { return value instanceof UUID || value instanceof YearMonth ? value.toString() : value; }
    }
}
