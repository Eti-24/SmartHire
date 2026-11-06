package smarthire;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import smarthire.applications.*;
import smarthire.cv.*;
import smarthire.documents.ManagedDocuments;
import smarthire.identity.*;
import smarthire.jobs.*;
import smarthire.skills.Skill;
import smarthire.storage.*;

/** Isolated real-database workflow data, removed by ownership rather than resetting shared tables. */
public final class WorkflowFixture implements AutoCloseable {
    public final Database database = Database.load(Path.of(".local/application.properties"), true);
    public final List<Long> accounts = new ArrayList<>();
    public final IdentityService identity;
    public final JobService jobs;
    public final CvService cvs;
    public final ApplicationService applications;
    public final CvApprovalService approvals;
    public final ManagedDocuments documents;
    public final UUID admin, company, foreignCompany, student, secondStudent;
    public final long companyId, category;
    public final String skill = "workflow-" + UUID.randomUUID(), otherSkill = "workflow-" + UUID.randomUUID();
    public final String initialPolicy;
    public final List<String> policies = new ArrayList<>();
    public final Path pdf = Path.of("src/test/resources/documents/fictional-cv.pdf");

    public WorkflowFixture(Path root) {
        database.initialize();
        var accountsRepository = new JdbcAccounts(database); identity = new IdentityService(accountsRepository);
        String hash = Passwords.hash("Workflow-test!21".toCharArray());
        admin = account(accountsRepository, Role.ADMIN, hash); company = account(accountsRepository, Role.COMPANY, hash);
        foreignCompany = account(accountsRepository, Role.COMPANY, hash);
        student = account(accountsRepository, Role.STUDENT, hash); secondStudent = account(accountsRepository, Role.STUDENT, hash);
        companyId = identity.profile(company).id();
        documents = new ManagedDocuments(root, new JdbcDocumentReferences(database));
        jobs = new JobService(identity, new JdbcJobs(database), documents);
        initialPolicy = jobs.scoringPolicy(admin).version();
        category = jobs.saveCategory(admin, 0, "Workflow " + UUID.randomUUID());
        jobs.saveSkill(admin, new Skill(skill, skill, Skill.Type.TECHNICAL, Set.of("alias-" + skill)));
        jobs.saveSkill(admin, new Skill(otherSkill, otherSkill, Skill.Type.SOFT, Set.of()));
        jobs.verify(admin, companyId, true);
        cvs = new CvService(identity, new JdbcCvs(database), documents);
        applications = new ApplicationService(identity, new JdbcApplications(database), documents);
        approvals = new CvApprovalService(identity, new JdbcCvApprovals(database), documents);
    }
    private UUID account(JdbcAccounts repo, Role role, String hash) {
        var account = repo.create(UUID.randomUUID() + "@workflow.example", hash, role, "Fictional Workflow Test"); accounts.add(account.id());
        return identity.login(account.email(), "Workflow-test!21".toCharArray());
    }
    public UUID newStudent() {
        return account(new JdbcAccounts(database), Role.STUDENT, Passwords.hash("Workflow-test!21".toCharArray()));
    }
    public Job.Details details(List<Job.Requirement> requirements) {
        return new Job.Details("Fictional workflow role", "Fictional job description", category, "Dhaka", BigDecimal.ZERO, BigDecimal.TEN, requirements);
    }
    public Job.Details details() { return details(List.of(new Job.Requirement(Job.Kind.SKILL, skill, 0, Job.Priority.REQUIRED, true))); }
    public long publish() { return publish(details()); }
    public long publish(Job.Details details) { long id = jobs.submit(company, 0, details); jobs.moderate(admin, id, true, ""); return id; }
    public CvProfile confirm(UUID session, List<String> skills) {
        var cv = confirmPending(session, skills);
        approvals.decide(admin, cv.id(), CvApproval.Status.APPROVED, ""); return cv;
    }
    public CvProfile confirmPending(UUID session, List<String> skills) {
        var q = new CvProfile.Qualifications(skills, Job.Degree.BACHELOR, "computer science", List.of(), List.of(), List.of());
        try (var draft = cvs.current(session).isEmpty() ? cvs.upload(session, pdf) : cvs.edit(session)) { return cvs.confirm(session, draft, q); }
    }
    public void execute(String sql, Object... values) throws Exception {
        try (var connection = database.connect(); var query = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) { query.setObject(i + 1, values[i]); } query.executeUpdate();
        }
    }
    @Override public void close() throws Exception {
        documents.close();
        execute("UPDATE scoring_policy_current SET version=? WHERE id=1", initialPolicy);
        for (String version : policies) { execute("DELETE FROM scoring_policies WHERE version=?", version); }
        for (long id : accounts) {
            execute("DELETE FROM admin_activity WHERE admin_id=?", id);
            var circulars = new ArrayList<String>();
            try (var connection = database.connect(); var query = connection.prepareStatement("SELECT c.document_id FROM job_circular_versions c JOIN jobs j ON j.id=c.job_id WHERE j.company_id=?")) {
                query.setLong(1, id); try (var rows = query.executeQuery()) { while (rows.next()) { circulars.add(rows.getString(1)); } }
            }
            execute("DELETE c FROM job_circulars c JOIN jobs j ON j.id=c.job_id WHERE j.company_id=?", id);
            execute("DELETE c FROM job_circular_versions c JOIN jobs j ON j.id=c.job_id WHERE j.company_id=?", id);
            for (String circular : circulars) { execute("DELETE FROM managed_documents WHERE id=?", circular); }
            execute("DELETE FROM applications WHERE student_id=?", id);
        }
        for (long id : accounts) {
            execute("DELETE r FROM job_requirements r JOIN jobs j ON j.id=r.job_id WHERE j.company_id=?", id);
            execute("DELETE FROM jobs WHERE company_id=?", id);
            var docs = new ArrayList<String>();
            try (var connection = database.connect(); var query = connection.prepareStatement("SELECT DISTINCT document_id FROM cv_versions WHERE student_id=?")) {
                query.setLong(1, id); try (var rows = query.executeQuery()) { while (rows.next()) { docs.add(rows.getString(1)); } }
            }
            execute("DELETE FROM cv_current WHERE student_id=?", id);
            for (String table : List.of("cv_evidence_skills", "cv_evidence", "cv_skills", "cv_certifications")) {
                execute("DELETE c FROM " + table + " c JOIN cv_versions v ON v.id=c.version_id WHERE v.student_id=?", id);
            }
            execute("DELETE FROM cv_versions WHERE student_id=?", id);
            for (String document : docs) { execute("DELETE FROM managed_documents WHERE id=?", document); }
            execute("DELETE FROM students WHERE user_id=?", id); execute("DELETE FROM companies WHERE user_id=?", id); execute("DELETE FROM users WHERE id=?", id);
        }
        execute("DELETE FROM categories WHERE id=?", category);
        for (String id : List.of(skill, otherSkill)) { execute("DELETE FROM skill_aliases WHERE skill_id=?", id); execute("DELETE FROM skills WHERE id=?", id); }
    }
}
