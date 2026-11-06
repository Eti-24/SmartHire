package smarthire.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import smarthire.applications.Application;
import smarthire.applications.ApplicationRepository;
import smarthire.applications.ApplicationTimeline;
import smarthire.applications.Interview;
import smarthire.applications.RecruiterFeedback;
import smarthire.cv.CvRepository;
import smarthire.identity.Profile;
import smarthire.identity.Role;
import smarthire.identity.UserError;
import smarthire.jobs.JobRepository;

public final class JdbcApplications implements ApplicationRepository {
    private final Database database;
    public JdbcApplications(Database database) { this.database = database; }

    @Override public <T> T transaction(Function<Store, T> work) {
        try (var connection = database.connect()) {
            connection.setAutoCommit(false);
            try {
                try (var lock = connection.prepareStatement("SELECT id FROM job_catalog_lock WHERE id=1 FOR UPDATE"); var rows = lock.executeQuery()) { rows.next(); }
                JdbcJobs.lockPolicy(connection);
                T result = work.apply(new Session(connection));
                connection.commit(); return result;
            } catch (RuntimeException | SQLException e) { connection.rollback(); throw e; }
        } catch (SQLException e) { throw Database.unavailable(); }
    }

    private static final class Session implements Store {
        private final Connection connection;
        Session(Connection connection) { this.connection = connection; }
        @Override public List<Interview> interviewHistory(UUID id) { return new JdbcInterviews(connection).history(id); }
        @Override public List<Interview> currentInterviews(Long studentId, Long companyId, Long jobId) {
            return new JdbcInterviews(connection).current(studentId, companyId, jobId);
        }
        @Override public void addInterview(Interview event, long studentId, long companyId) {
            new JdbcInterviews(connection).add(event, studentId, companyId);
        }

        @Override public RecruiterFeedback feedback(UUID id) {
            String sql = "SELECT n.*,f.interview_revision,f.technical,f.communication,f.role_fit,f.comments FROM application_notes n"
                    + " LEFT JOIN interview_feedback f ON f.application_id=n.application_id WHERE n.application_id=?";
            try (var query = connection.prepareStatement(sql)) {
                query.setString(1, id.toString());
                try (var row = query.executeQuery()) {
                    if (!row.next()) { return new RecruiterFeedback(0, "", null, null); }
                    var interview = row.getObject("interview_revision", Integer.class);
                    var scorecard = interview == null ? null : new RecruiterFeedback.Scorecard(interview,
                            row.getObject("technical", Integer.class), row.getObject("communication", Integer.class),
                            row.getObject("role_fit", Integer.class), row.getString("comments"));
                    var updated = row.getObject("updated_utc", java.time.LocalDateTime.class).toInstant(java.time.ZoneOffset.UTC);
                    return new RecruiterFeedback(row.getInt("revision"), row.getString("notes"), scorecard, updated);
                }
            } catch (SQLException e) { throw Database.unavailable(); }
        }

        @Override public void saveFeedback(UUID id, RecruiterFeedback feedback) {
            String key = id.toString();
            try (var query = connection.prepareStatement("INSERT INTO application_notes VALUES (?,?,?,?)"
                    + " AS incoming ON DUPLICATE KEY UPDATE revision=incoming.revision,notes=incoming.notes,updated_utc=incoming.updated_utc")) {
                query.setString(1, key);
                query.setInt(2, feedback.revision());
                query.setString(3, feedback.notes());
                query.setObject(4, java.time.LocalDateTime.ofInstant(feedback.updatedAt(), java.time.ZoneOffset.UTC));
                query.executeUpdate();
            } catch (SQLException e) { throw Database.unavailable(); }
            var score = feedback.scorecard();
            String sql = score == null ? "DELETE FROM interview_feedback WHERE application_id=?"
                    : "INSERT INTO interview_feedback VALUES (?,?,1,?,?,?,?) AS incoming ON DUPLICATE KEY UPDATE"
                      + " interview_revision=incoming.interview_revision,technical=incoming.technical,communication=incoming.communication,"
                      + " role_fit=incoming.role_fit,comments=incoming.comments";
            try (var query = connection.prepareStatement(sql)) {
                query.setString(1, key);
                if (score != null) {
                    query.setInt(2, score.interviewRevision());
                    query.setObject(3, score.technical());
                    query.setObject(4, score.communication());
                    query.setObject(5, score.roleFit());
                    query.setString(6, score.comments());
                }
                query.executeUpdate();
            } catch (SQLException e) { throw Database.unavailable(); }
        }
        @Override public JobRepository.Store jobs() { return new JdbcJobs.Session(connection); }
        @Override public CvRepository.Store cvs(long studentId) { return new JdbcCvs.Session(connection, studentId); }
        @Override public Profile activeActor(long id, Role role) {
            try (var query = connection.prepareStatement("SELECT * FROM users WHERE id=? FOR UPDATE")) {
                query.setLong(1, id);
                try (var row = query.executeQuery()) {
                    if (!row.next() || !row.getBoolean("active") || !row.getString("role").equals(role.name())) { throw new UserError("An active account with the correct role is required."); }
                    return new Profile(id, row.getString("email"), role, row.getString("display_name"), row.getString("phone"), row.getString("affiliation"), row.getString("about"));
                }
            } catch (SQLException e) { throw Database.unavailable(); }
        }
        @Override public List<Application> list(long actorId, Role role) {
            return query("SELECT a.* FROM applications a JOIN jobs j ON j.id=a.job_id WHERE "
                    + (role == Role.STUDENT ? "a.student_id=?" : "j.company_id=?") + " ORDER BY a.submitted_at,a.id", actorId);
        }
        @Override public Optional<Application> lock(UUID id) {
            return query("SELECT a.* FROM applications a WHERE a.id=? FOR UPDATE", id.toString()).stream().findFirst();
        }
        private List<Application> query(String sql, Object value) {
            try (var query = connection.prepareStatement(sql)) {
                query.setObject(1, value);
                try (var rows = query.executeQuery()) {
                    var results = new ArrayList<Application>();
                    while (rows.next()) {
                        long student = rows.getLong("student_id");
                        Profile profile = new Profile(student, rows.getString("student_email"), Role.STUDENT, rows.getString("student_name"),
                                rows.getString("student_phone"), rows.getString("student_affiliation"), rows.getString("student_about"));
                        results.add(new Application(UUID.fromString(rows.getString("id")), student, rows.getLong("job_id"), UUID.fromString(rows.getString("cv_version_id")),
                                rows.getTimestamp("submitted_at").toInstant(), Application.Status.valueOf(rows.getString("status")), profile,
                                rows.getString("original_policy"), rows.getDouble("original_score"), rows.getString("original_explanation")));
                    }
                    return List.copyOf(results);
                }
            } catch (SQLException e) { throw Database.unavailable(); }
        }
        @Override public void insert(Application a) {
            Profile p = a.submittedProfile();
            update("INSERT INTO applications(id,student_id,job_id,cv_version_id,submitted_at,status,student_name,student_email,student_phone,student_affiliation,student_about,original_policy,original_score,original_explanation) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    a.id().toString(), a.studentId(), a.jobId(), a.cvVersion().toString(), Timestamp.from(a.submittedAt()), a.status().name(),
                    p.name(), p.email(), p.phone(), p.affiliation(), p.about(), a.originalPolicy(), a.originalScore(), a.originalExplanation());
            notifyParticipants(a, "Application submitted");
        }
        @Override public void status(Application previous, Application.Status status, Role actorRole, Instant time) {
            String id = previous.id().toString();
            update("UPDATE applications SET status=?,hired_at=CASE WHEN ?='HIRED' THEN ? ELSE hired_at END WHERE id=?",
                    status.name(), status.name(), Timestamp.from(time), id);
            update("INSERT INTO application_status_events(application_id,sequence_no,previous_status,new_status,actor_role,changed_utc)"
                    + " SELECT ?,COALESCE(MAX(sequence_no),0)+1,?,?,?,? FROM application_status_events WHERE application_id=?",
                    id, previous.status().name(), status.name(), actorRole.name(), LocalDateTime.ofInstant(time, ZoneOffset.UTC), id);
            notifyParticipants(previous, "Application " + status.name().toLowerCase(java.util.Locale.ROOT));
        }

        @Override public List<ApplicationTimeline.Event> statusEvents(UUID id) {
            try (var query = connection.prepareStatement("SELECT new_status,changed_utc FROM application_status_events"
                    + " WHERE application_id=? ORDER BY sequence_no")) {
                query.setString(1, id.toString());
                try (var rows = query.executeQuery()) {
                    var events = new ArrayList<ApplicationTimeline.Event>();
                    while (rows.next()) {
                        var instant = rows.getObject("changed_utc", LocalDateTime.class).toInstant(ZoneOffset.UTC);
                        events.add(new ApplicationTimeline.Event(instant, rows.getString("new_status")));
                    }
                    return List.copyOf(events);
                }
            } catch (SQLException e) { throw Database.unavailable(); }
        }

        @Override public Optional<Instant> hiredAt(UUID id) {
            try (var query = connection.prepareStatement("SELECT hired_at FROM applications WHERE id=?")) {
                query.setString(1, id.toString());
                try (var rows = query.executeQuery()) {
                    return rows.next() ? Optional.ofNullable(rows.getTimestamp(1)).map(Timestamp::toInstant) : Optional.empty();
                }
            } catch (SQLException e) { throw Database.unavailable(); }
        }
        private void notifyParticipants(Application a, String event) {
            var job = jobs().lockJob(a.jobId()).orElseThrow();
            String message = event + " for job #" + a.jobId() + ". Application: " + a.id();
            JdbcNotifications.add(connection, a.studentId(), message);
            JdbcNotifications.add(connection, job.companyId(), message);
        }
        private void update(String sql, Object... values) {
            try (var statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < values.length; i++) { statement.setObject(i + 1, values[i]); }
                statement.executeUpdate();
            } catch (SQLException e) {
                if (e.getErrorCode() == 1062) { throw new UserError("You have already applied to this job. Withdrawal does not allow another submission."); }
                throw Database.unavailable();
            }
        }
    }
}
