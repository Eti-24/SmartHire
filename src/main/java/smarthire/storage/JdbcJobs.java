package smarthire.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import smarthire.identity.UserError;
import smarthire.jobs.Job;
import smarthire.jobs.JobRepository;
import smarthire.jobs.JobSearch;
import smarthire.jobs.SavedSearch;
import smarthire.jobs.JobService.SavedJob;
import smarthire.jobs.JobService.SavedStatus;
import smarthire.skills.Skill;
import smarthire.documents.StoredDocument;
import smarthire.matching.ScoringPolicy;
import static smarthire.jobs.Job.*;

public final class JdbcJobs implements JobRepository {
    static final String HIRED_COUNT = "(SELECT COUNT(*) FROM applications a WHERE a.job_id=j.id AND a.status='HIRED')";
    static final String FILLED_JOB = "(" + HIRED_COUNT + ">=j.vacancy_count)";
    static final String OPEN_AT = "j.status='APPROVED' AND c.verified=TRUE AND u.active=TRUE AND NOT "
            + FILLED_JOB + " AND (j.application_deadline_at IS NULL OR j.application_deadline_at>?)";
    private static final String POSTINGS = "SELECT j.*,u.display_name," + HIRED_COUNT + " AS hired_count"
            + " FROM jobs j JOIN users u ON u.id=j.company_id";
    private final Database database;
    public JdbcJobs(Database database) { this.database = database; }

    @Override
    public <T> T transaction(Function<Store, T> work) {
        try (Connection connection = database.connect()) {
            connection.setAutoCommit(false);
            try {
                // Serialize catalog and publication changes in this single-machine demonstration.
                try (var lock = connection.prepareStatement("SELECT id FROM job_catalog_lock WHERE id = 1 FOR UPDATE");
                        var ignored = lock.executeQuery()) { ignored.next(); }
                lockPolicy(connection);
                T result = work.apply(new Session(connection));
                connection.commit();
                return result;
            } catch (RuntimeException | SQLException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) { throw Database.unavailable(); }
    }

    static void lockPolicy(Connection connection) throws SQLException {
        try (var query = connection.prepareStatement("SELECT version FROM scoring_policy_current WHERE id=1 FOR UPDATE"); var row = query.executeQuery()) { row.next(); }
    }

    static final class Session implements Store {
        private final Connection connection;
        Session(Connection connection) { this.connection = connection; }

        @Override public void audit(long adminId, smarthire.workspace.AdminActivity.Change change) {
            JdbcAdminActivity.add(connection, adminId, change);
        }

        private int update(String sql, Object... values) {
            try (var statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < values.length; i++) { statement.setObject(i + 1, values[i]); }
                return statement.executeUpdate();
            } catch (SQLException exception) { throw failure(exception); }
        }

        @Override public boolean active(long id) {
            try (var statement = connection.prepareStatement("SELECT active FROM users WHERE id = ? FOR SHARE")) {
                statement.setLong(1, id);
                try (var rows = statement.executeQuery()) { return rows.next() && rows.getBoolean(1); }
            } catch (SQLException exception) { throw failure(exception); }
        }

        @Override public List<Company> companies() {
            var result = new ArrayList<Company>();
            try (var statement = connection.prepareStatement("SELECT u.id,u.display_name,u.active,c.verified FROM companies c JOIN users u ON u.id=c.user_id ORDER BY u.id");
                    var rows = statement.executeQuery()) {
                while (rows.next()) { result.add(new Company(rows.getLong(1), rows.getString(2), rows.getBoolean(3), rows.getBoolean(4))); }
                return List.copyOf(result);
            } catch (SQLException exception) { throw failure(exception); }
        }

        @Override public void verify(long id, boolean verified) {
            boolean previous = companies().stream().filter(c -> c.id() == id).findFirst().orElseThrow().verified();
            update("UPDATE companies SET verified=? WHERE user_id=?", verified, id);
            if (previous != verified) {
                JdbcNotifications.add(connection, id, verified ? "Your company has been verified." : "Your company verification has been removed.");
            }
        }

        @Override public List<Category> categories() {
            var result = new ArrayList<Category>();
            try (var statement = connection.prepareStatement("SELECT id,name FROM categories ORDER BY name"); var rows = statement.executeQuery()) {
                while (rows.next()) { result.add(new Category(rows.getLong(1), rows.getString(2))); }
                return List.copyOf(result);
            } catch (SQLException exception) { throw failure(exception); }
        }

        @Override public long saveCategory(long id, String name) {
            if (id != 0) { update("UPDATE categories SET name=? WHERE id=?", name, id); return id; }
            try (var statement = connection.prepareStatement("INSERT INTO categories(name) VALUES (?)", Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, name); statement.executeUpdate();
                try (var keys = statement.getGeneratedKeys()) { keys.next(); return keys.getLong(1); }
            } catch (SQLException exception) { throw failure(exception); }
        }

        @Override public List<Skill> skills() {
            var result = new ArrayList<Skill>();
            try (var statement = connection.prepareStatement("SELECT id,name,type FROM skills ORDER BY id"); var rows = statement.executeQuery()) {
                while (rows.next()) {
                    var aliases = new HashSet<String>();
                    try (var query = connection.prepareStatement("SELECT phrase FROM skill_aliases WHERE skill_id=?")) {
                        query.setString(1, rows.getString(1));
                        try (var phrases = query.executeQuery()) { while (phrases.next()) { aliases.add(phrases.getString(1)); } }
                    }
                    result.add(new Skill(rows.getString(1), rows.getString(2), Skill.Type.valueOf(rows.getString(3)), aliases));
                }
                return List.copyOf(result);
            } catch (SQLException exception) { throw failure(exception); }
        }

        @Override public void saveSkill(Skill skill) {
            update("INSERT INTO skills(id,name,type) VALUES (?,?,?) ON DUPLICATE KEY UPDATE name=?,type=?",
                    skill.id(), skill.name(), skill.type().name(), skill.name(), skill.type().name());
            update("DELETE FROM skill_aliases WHERE skill_id=?", skill.id());
            for (String alias : skill.aliases()) { update("INSERT INTO skill_aliases(skill_id,phrase) VALUES (?,?)", skill.id(), alias); }
        }

        @Override public List<Job> jobs() {
            return queryJobs(POSTINGS + " ORDER BY j.id", List.of());
        }

        @Override public List<Job> search(JobSearch criteria, Long companyId, Instant availableAt) {
            return search(criteria, companyId, availableAt, null);
        }

        private List<Job> search(JobSearch criteria, Long companyId, Instant availableAt, Long jobId) {
            var sql = new StringBuilder(POSTINGS).append(" JOIN companies c ON c.user_id=j.company_id WHERE TRUE");
            var values = new ArrayList<Object>();
            if (availableAt != null) { filter(sql, values, OPEN_AT, utc(availableAt)); }
            filter(sql, values, "j.id=?", jobId);
            filter(sql, values, "j.company_id=?", companyId);
            filter(sql, values, "LOCATE(CAST(? AS BINARY),CAST(LOWER(j.title) AS BINARY))>0",
                    criteria.title().isEmpty() ? null : criteria.title());
            filter(sql, values, "j.category_id=?", criteria.categoryId());
            filter(sql, values, "LOCATE(CAST(? AS BINARY),CAST(LOWER(j.location) AS BINARY))>0",
                    criteria.location().isEmpty() ? null : criteria.location());
            filter(sql, values, "j.salary_max>=?", criteria.minimumSalary());
            filter(sql, values, "j.salary_min<=?", criteria.maximumSalary());
            filter(sql, values, "j.application_deadline_at>=?", utc(criteria.closingFrom()));
            filter(sql, values, "j.application_deadline_at<?", utc(criteria.closingBefore()));
            return queryJobs(sql.append(" ORDER BY j.id").toString(), values);
        }

        @Override public List<SavedSearch> savedSearches(long studentId) {
            try (var query = connection.prepareStatement("SELECT * FROM saved_job_searches WHERE student_id=? ORDER BY id")) {
                query.setLong(1, studentId);
                try (var rows = query.executeQuery()) {
                    var result = new ArrayList<SavedSearch>();
                    while (rows.next()) {
                        result.add(new SavedSearch(rows.getLong("id"), rows.getString("name"), searchCriteria(rows), rows.getBoolean("active")));
                    }
                    return List.copyOf(result);
                }
            } catch (SQLException exception) { throw failure(exception); }
        }

        @Override public void saveSearch(long studentId, SavedSearch search) {
            var criteria = search.criteria();
            String columns = "name=?,title=?,category_id=?,location=?,minimum_salary=?,maximum_salary=?,closing_from=?,closing_before=?,active=?";
            if (search.id() == 0) {
                update("INSERT INTO saved_job_searches SET " + columns + ",student_id=?",
                        search.name(), criteria.title(), criteria.categoryId(), criteria.location(), criteria.minimumSalary(),
                        criteria.maximumSalary(), utc(criteria.closingFrom()), utc(criteria.closingBefore()), search.active(), studentId);
            } else {
                if (savedSearches(studentId).stream().noneMatch(row -> row.id() == search.id())) {
                    throw new UserError("Saved search not found.");
                }
                update("UPDATE saved_job_searches SET " + columns + " WHERE student_id=? AND id=?",
                        search.name(), criteria.title(), criteria.categoryId(), criteria.location(), criteria.minimumSalary(),
                        criteria.maximumSalary(), utc(criteria.closingFrom()), utc(criteria.closingBefore()), search.active(), studentId, search.id());
            }
        }

        @Override public void deleteSearch(long studentId, long searchId) {
            update("DELETE FROM saved_job_searches WHERE student_id=? AND id=?", studentId, searchId);
        }

        @Override public void notifySavedSearches(long jobId, Instant now) {
            String sql = "SELECT s.* FROM saved_job_searches s JOIN users u ON u.id=s.student_id"
                    + " WHERE s.active=TRUE AND u.active=TRUE AND NOT EXISTS (SELECT 1 FROM saved_search_notices n"
                    + " WHERE n.student_id=s.student_id AND n.job_id=?) ORDER BY s.student_id,s.id";
            try (var query = connection.prepareStatement(sql)) {
                query.setLong(1, jobId);
                try (var rows = query.executeQuery()) {
                    var notified = new HashSet<Long>();
                    while (rows.next()) {
                        long student = rows.getLong("student_id");
                        if (notified.contains(student) || search(searchCriteria(rows), null, now, jobId).isEmpty()) { continue; }
                        var notice = JdbcNotifications.add(connection, student, "A posting matched one of your saved searches.");
                        update("INSERT INTO saved_search_notices(notice_id,student_id,job_id) VALUES (?,?,?)",
                                notice.toString(), student, jobId);
                        notified.add(student);
                    }
                }
            } catch (SQLException exception) { throw failure(exception); }
        }

        private static JobSearch searchCriteria(java.sql.ResultSet row) throws SQLException {
            var from = row.getObject("closing_from", LocalDateTime.class);
            var before = row.getObject("closing_before", LocalDateTime.class);
            return new JobSearch(row.getString("title"), row.getObject("category_id", Long.class), row.getString("location"),
                    row.getBigDecimal("minimum_salary"), row.getBigDecimal("maximum_salary"),
                    from == null ? null : from.toInstant(ZoneOffset.UTC), before == null ? null : before.toInstant(ZoneOffset.UTC));
        }

        private static void filter(StringBuilder sql, List<Object> values, String predicate, Object value) {
            if (value != null) {
                sql.append(" AND ").append(predicate);
                values.add(value);
            }
        }

        @Override public List<SavedJob> savedJobs(long studentId, Instant now) {
            String sql = "SELECT j.id,j.title,b.saved_title,j.status,u.active,c.verified," + FILLED_JOB
                    + ",a.id,j.application_deadline_at<=? FROM saved_jobs b JOIN jobs j ON j.id=b.job_id JOIN users u ON u.id=j.company_id"
                    + " JOIN companies c ON c.user_id=j.company_id"
                    + " LEFT JOIN applications a ON a.student_id=b.student_id AND a.job_id=b.job_id"
                    + " WHERE b.student_id=? ORDER BY b.saved_at DESC,j.id DESC";
            var result = new ArrayList<SavedJob>();
            try (var query = connection.prepareStatement(sql)) {
                query.setObject(1, utc(now));
                query.setLong(2, studentId);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        Status status = Status.valueOf(rows.getString(4));
                        SavedStatus availability;
                        if (!rows.getBoolean(5) || !rows.getBoolean(6)) { availability = SavedStatus.UNAVAILABLE; }
                        else if (rows.getBoolean(7)) { availability = SavedStatus.FILLED; }
                        else if (status == Status.CLOSED) { availability = SavedStatus.CLOSED; }
                        else if (status == Status.APPROVED) { availability = rows.getBoolean(9) ? SavedStatus.DEADLINE_PASSED : SavedStatus.AVAILABLE; }
                        else { availability = SavedStatus.UNAVAILABLE; }
                        String title = switch (availability) {
                            case AVAILABLE, DEADLINE_PASSED -> rows.getString(2);
                            case CLOSED, FILLED -> rows.getString(3);
                            case UNAVAILABLE -> "Unavailable posting";
                        };
                        String application = rows.getString(8);
                        result.add(new SavedJob(rows.getLong(1), title, availability,
                                application == null ? null : java.util.UUID.fromString(application)));
                    }
                }
                return List.copyOf(result);
            } catch (SQLException exception) { throw failure(exception); }
        }

        @Override public void saveBookmark(long studentId, Job job) {
            update("INSERT INTO saved_jobs(student_id,job_id,saved_title) VALUES (?,?,?) ON DUPLICATE KEY UPDATE saved_title=saved_title",
                    studentId, job.id(), job.details().title());
        }

        @Override public void removeBookmark(long studentId, long jobId) {
            update("DELETE FROM saved_jobs WHERE student_id=? AND job_id=?", studentId, jobId);
        }

        @Override public List<HiringRecord> hiringHistory(Long companyId) {
            String sql = "SELECT j.id,j.title,u.display_name,c.name,j.location,j.vacancy_count FROM jobs j "
                    + "JOIN users u ON u.id=j.company_id JOIN categories c ON c.id=j.category_id WHERE " + FILLED_JOB
                    + (companyId == null ? "" : " AND j.company_id=?") + " ORDER BY j.id DESC";
            var result = new ArrayList<HiringRecord>();
            try (var query = connection.prepareStatement(sql)) {
                if (companyId != null) { query.setLong(1, companyId); }
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        result.add(new HiringRecord(rows.getLong(1), rows.getString(2), rows.getString(3),
                                rows.getString(4), rows.getString(5), rows.getInt(6)));
                    }
                }
                return List.copyOf(result);
            } catch (SQLException exception) { throw failure(exception); }
        }

        @Override public Optional<Job> lockJob(long id) {
            return queryJobs(POSTINGS + " WHERE j.id=? FOR UPDATE", List.of(id)).stream().findFirst();
        }

        @Override public boolean hasApplications(long jobId) {
            try (var query = connection.prepareStatement("SELECT id FROM applications WHERE job_id=? LIMIT 1")) {
                query.setLong(1, jobId);
                try (var rows = query.executeQuery()) { return rows.next(); }
            } catch (SQLException exception) { throw failure(exception); }
        }

        @Override public List<CompanyHire> companyHires(long companyId) {
            String sql = "SELECT a.id,j.id,j.title,a.student_name,a.hired_at,j.vacancy_count," + HIRED_COUNT
                    + " FROM jobs j JOIN applications a ON a.job_id=j.id WHERE j.company_id=? AND a.status='HIRED'"
                    + " ORDER BY a.hired_at DESC,j.id DESC,a.id";
            var result = new ArrayList<CompanyHire>();
            try (var query = connection.prepareStatement(sql)) {
                query.setLong(1, companyId);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        var hiredAt = rows.getTimestamp(5);
                        result.add(new CompanyHire(java.util.UUID.fromString(rows.getString(1)), rows.getLong(2), rows.getString(3),
                                rows.getString(4), hiredAt == null ? null : hiredAt.toInstant(), rows.getInt(6), rows.getLong(7)));
                    }
                }
            } catch (SQLException exception) { throw failure(exception); }
            return List.copyOf(result);
        }

        @Override public ScoringPolicy activePolicy() {
            try (var query = connection.prepareStatement("SELECT p.* FROM scoring_policies p JOIN scoring_policy_current c ON c.version=p.version WHERE c.id=1"); var row = query.executeQuery()) {
                if (!row.next()) { throw Database.unavailable(); }
                var weights = new java.util.EnumMap<ScoringPolicy.Category, Integer>(ScoringPolicy.Category.class);
                for (var category : ScoringPolicy.Category.values()) { weights.put(category, row.getInt(category.name().toLowerCase(java.util.Locale.ROOT))); }
                return new ScoringPolicy(row.getString("version"), weights);
            } catch (SQLException e) { throw failure(e); }
        }

        @Override public void activatePolicy(ScoringPolicy policy) {
            var w = policy.weights();
            update("INSERT INTO scoring_policies(version,skills,education,experience,projects,certifications) VALUES (?,?,?,?,?,?)",
                    policy.version(), w.getOrDefault(ScoringPolicy.Category.SKILLS, 0), w.getOrDefault(ScoringPolicy.Category.EDUCATION, 0),
                    w.getOrDefault(ScoringPolicy.Category.EXPERIENCE, 0), w.getOrDefault(ScoringPolicy.Category.PROJECTS, 0), w.getOrDefault(ScoringPolicy.Category.CERTIFICATIONS, 0));
            update("UPDATE scoring_policy_current SET version=? WHERE id=1", policy.version());
        }

        @Override public Optional<StoredDocument> circular(long jobId) {
            try (var query = connection.prepareStatement("SELECT d.* FROM managed_documents d JOIN job_circulars c ON c.document_id=d.id WHERE c.job_id=?")) {
                query.setLong(1, jobId);
                try (var row = query.executeQuery()) {
                    if (!row.next()) { return Optional.empty(); }
                    return Optional.of(new StoredDocument(java.util.UUID.fromString(row.getString("id")), StoredDocument.Format.valueOf(row.getString("format")),
                            row.getString("original_name"), row.getLong("byte_size"), row.getString("sha256"), row.getString("extracted_text")));
                }
            } catch (SQLException e) { throw failure(e); }
        }
        @Override public void attach(long jobId, StoredDocument document) {
            try { JdbcDocumentReferences.insert(connection, document); }
            catch (SQLException e) { throw failure(e); }
            update("INSERT INTO job_circular_versions(job_id,document_id) VALUES (?,?)", jobId, document.id().toString());
            update("INSERT INTO job_circulars(job_id,document_id) VALUES (?,?) ON DUPLICATE KEY UPDATE document_id=?", jobId, document.id().toString(), document.id().toString());
        }

        private List<Job> queryJobs(String sql, List<Object> parameters) {
            var result = new ArrayList<Job>();
            try (var statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < parameters.size(); i++) { statement.setObject(i + 1, parameters.get(i)); }
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        long jobId = rows.getLong("id");
                        var requirements = new ArrayList<Requirement>();
                        try (var query = connection.prepareStatement("SELECT * FROM job_requirements WHERE job_id=? ORDER BY ordinal")) {
                            query.setLong(1, jobId);
                            try (var items = query.executeQuery()) {
                                while (items.next()) { requirements.add(new Requirement(Kind.valueOf(items.getString("kind")), items.getString("value"),
                                        items.getInt("months"), Priority.valueOf(items.getString("priority")), items.getBoolean("mandatory"))); }
                            }
                        }
                        var time = rows.getObject("application_deadline_at", LocalDateTime.class);
                        var deadline = time == null ? null : new Deadline(time.toInstant(ZoneOffset.UTC),
                                ZoneId.of(rows.getString("application_deadline_zone")));
                        result.add(new Job(jobId, rows.getLong("company_id"), rows.getString("display_name"),
                                new Details(rows.getString("title"), rows.getString("description"), rows.getLong("category_id"),
                                        rows.getString("location"), rows.getBigDecimal("salary_min"), rows.getBigDecimal("salary_max"),
                                        requirements, rows.getInt("vacancy_count"), deadline),
                                Status.valueOf(rows.getString("status")), rows.getString("decision"), rows.getLong("hired_count")));
                    }
                }
                return List.copyOf(result);
            } catch (SQLException exception) { throw failure(exception); }
        }

        @Override public long saveJob(long companyId, long id, Details details) {
            var deadline = details.deadline();
            LocalDateTime time = deadline == null ? null : utc(deadline.time());
            String zone = deadline == null ? null : deadline.zone().getId();
            if (id == 0) {
                try (var statement = connection.prepareStatement("INSERT INTO jobs(company_id,title,description,category_id,location,salary_min,salary_max,vacancy_count,application_deadline_at,application_deadline_zone) VALUES (?,?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                    statement.setLong(1, companyId); statement.setString(2, details.title()); statement.setString(3, details.description());
                    statement.setLong(4, details.categoryId()); statement.setString(5, details.location());
                    statement.setBigDecimal(6, details.salaryMin()); statement.setBigDecimal(7, details.salaryMax());
                    statement.setInt(8, details.vacancyCount());
                    statement.setObject(9, time); statement.setString(10, zone);
                    statement.executeUpdate();
                    try (var keys = statement.getGeneratedKeys()) { keys.next(); id = keys.getLong(1); }
                } catch (SQLException exception) { throw failure(exception); }
            } else {
                update("UPDATE jobs SET title=?,description=?,category_id=?,location=?,salary_min=?,salary_max=?,vacancy_count=?,application_deadline_at=?,application_deadline_zone=?,status='PENDING',decision='' WHERE id=?",
                        details.title(), details.description(), details.categoryId(), details.location(), details.salaryMin(), details.salaryMax(), details.vacancyCount(), time, zone, id);
                update("DELETE FROM job_requirements WHERE job_id=?", id);
            }
            int ordinal = 0;
            for (Requirement r : details.requirements()) {
                update("INSERT INTO job_requirements(job_id,ordinal,kind,value,months,priority,mandatory) VALUES (?,?,?,?,?,?,?)",
                        id, ordinal++, r.kind().name(), r.value(), r.months(), r.priority().name(), r.mandatory());
            }
            return id;
        }

        private static LocalDateTime utc(Instant time) {
            return time == null ? null : LocalDateTime.ofInstant(time.truncatedTo(java.time.temporal.ChronoUnit.MICROS), ZoneOffset.UTC);
        }

        @Override public void status(long id, Status status, String decision) {
            Job previous = lockJob(id).orElseThrow();
            update("UPDATE jobs SET status=?,decision=? WHERE id=?", status.name(), decision, id);
            if (previous.status() != status && (status == Status.APPROVED || status == Status.REJECTED)) {
                JdbcNotifications.add(connection, previous.companyId(), "Job #" + id + " " + status.name().toLowerCase(java.util.Locale.ROOT) + ": " + decision);
            }
        }

        private static UserError failure(SQLException exception) {
            return exception.getErrorCode() == 1062 ? new UserError("That category or skill alias already exists.") : Database.unavailable();
        }
    }
}
