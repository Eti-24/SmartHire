package smarthire.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import smarthire.identity.UserError;
import smarthire.jobs.Job;
import smarthire.jobs.JobRepository;
import smarthire.skills.Skill;
import smarthire.documents.StoredDocument;
import smarthire.matching.ScoringPolicy;
import static smarthire.jobs.Job.*;

public final class JdbcJobs implements JobRepository {
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
            return queryJobs("SELECT j.*,u.display_name FROM jobs j JOIN users u ON u.id=j.company_id ORDER BY j.id", null);
        }

        @Override public Optional<Job> lockJob(long id) {
            return queryJobs("SELECT j.*,u.display_name FROM jobs j JOIN users u ON u.id=j.company_id WHERE j.id=? FOR UPDATE", id).stream().findFirst();
        }

        @Override public boolean hasApplications(long jobId) {
            try (var query = connection.prepareStatement("SELECT id FROM applications WHERE job_id=? LIMIT 1")) {
                query.setLong(1, jobId);
                try (var rows = query.executeQuery()) { return rows.next(); }
            } catch (SQLException exception) { throw failure(exception); }
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

        private List<Job> queryJobs(String sql, Long id) {
            var result = new ArrayList<Job>();
            try (var statement = connection.prepareStatement(sql)) {
                if (id != null) { statement.setLong(1, id); }
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
                        result.add(new Job(jobId, rows.getLong("company_id"), rows.getString("display_name"),
                                new Details(rows.getString("title"), rows.getString("description"), rows.getLong("category_id"),
                                        rows.getString("location"), rows.getBigDecimal("salary_min"), rows.getBigDecimal("salary_max"), requirements),
                                Status.valueOf(rows.getString("status")), rows.getString("decision")));
                    }
                }
                return List.copyOf(result);
            } catch (SQLException exception) { throw failure(exception); }
        }

        @Override public long saveJob(long companyId, long id, Details details) {
            if (id == 0) {
                try (var statement = connection.prepareStatement("INSERT INTO jobs(company_id,title,description,category_id,location,salary_min,salary_max) VALUES (?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                    statement.setLong(1, companyId); statement.setString(2, details.title()); statement.setString(3, details.description());
                    statement.setLong(4, details.categoryId()); statement.setString(5, details.location());
                    statement.setBigDecimal(6, details.salaryMin()); statement.setBigDecimal(7, details.salaryMax()); statement.executeUpdate();
                    try (var keys = statement.getGeneratedKeys()) { keys.next(); id = keys.getLong(1); }
                } catch (SQLException exception) { throw failure(exception); }
            } else {
                update("UPDATE jobs SET title=?,description=?,category_id=?,location=?,salary_min=?,salary_max=?,status='PENDING',decision='' WHERE id=?",
                        details.title(), details.description(), details.categoryId(), details.location(), details.salaryMin(), details.salaryMax(), id);
                update("DELETE FROM job_requirements WHERE job_id=?", id);
            }
            int ordinal = 0;
            for (Requirement r : details.requirements()) {
                update("INSERT INTO job_requirements(job_id,ordinal,kind,value,months,priority,mandatory) VALUES (?,?,?,?,?,?,?)",
                        id, ordinal++, r.kind().name(), r.value(), r.months(), r.priority().name(), r.mandatory());
            }
            return id;
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
