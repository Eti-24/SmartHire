package smarthire.cv;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import smarthire.documents.*;
import smarthire.identity.*;
import smarthire.jobs.Job.Degree;
import smarthire.skills.SkillVocabulary;
import smarthire.storage.*;
import static smarthire.cv.CvProfile.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "smarthire.integration", matches = "true")
class CvIntegrationTest {
    @TempDir Path root;
    private final Database database = Database.load(Path.of(".local/application.properties"), true);
    private final List<Long> accounts = new ArrayList<>();
    private final List<String> insertedSkills = new ArrayList<>();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-05T00:00:00Z"), ZoneOffset.UTC);
    private IdentityService identity;
    private CvService service;
    private ManagedDocuments documents;
    private UUID student, other, company, admin;
    private final Path pdf = Path.of("src/test/resources/documents/fictional-cv.pdf");
    private final Path docx = Path.of("src/test/resources/documents/fictional-cv.docx");
    private boolean failCommit;

    @BeforeEach void setup() throws Exception {
        database.initialize();
        var repository = new JdbcAccounts(database); identity = new IdentityService(repository);
        String hash = Passwords.hash("Cv-integration!21".toCharArray());
        student = account(repository, Role.STUDENT, hash); other = account(repository, Role.STUDENT, hash);
        company = account(repository, Role.COMPANY, hash); admin = account(repository, Role.ADMIN, hash);
        // Add only missing shared demo skills. Existing catalog entries are not overwritten.
        try (var connection = database.connect()) {
            for (var skill : SkillVocabulary.demo().skills()) {
                try (var statement = connection.prepareStatement("INSERT IGNORE INTO skills(id,name,type) VALUES (?,?,?)")) {
                    statement.setString(1, skill.id()); statement.setString(2, skill.name()); statement.setString(3, skill.type().name());
                    if (statement.executeUpdate() == 1) { insertedSkills.add(skill.id()); }
                }
            }
        }
        documents = new ManagedDocuments(root, new JdbcDocumentReferences(database));
        CvRepository repositoryWithFailure = new CvRepository() {
            @Override public <T> T transaction(long owner, java.util.function.Function<Store, T> work) {
                return new JdbcCvs(database).transaction(owner, store -> {
                    T result = work.apply(store);
                    if (failCommit) { throw new UserError("Simulated failure before commit"); }
                    return result;
                });
            }
        };
        service = new CvService(identity, repositoryWithFailure, documents, clock);
    }

    private UUID account(JdbcAccounts repository, Role role, String hash) {
        var account = repository.create(UUID.randomUUID() + "@cv.example", hash, role, "Fictional CV Test");
        accounts.add(account.id()); return identity.login(account.email(), "Cv-integration!21".toCharArray());
    }

    private Qualifications qualified() {
        return new Qualifications(List.of("Java", "SQL", "Communication"), Degree.BACHELOR, " Computer  Science ",
                List.of(new Project("Fictional student portal", List.of("Java"))),
                List.of(new Experience("Fictional internship", YearMonth.of(2025, 1), YearMonth.of(2025, 6), false, List.of("Java")),
                        new Experience("Fictional ongoing role", YearMonth.of(2026, 1), null, true, List.of("SQL"))), List.of(" Demo  Certificate "));
    }

    @Test void reviewPersistsCanonicalQualificationsAndImmutableOriginalsAcrossReplacementAndRestart() throws Exception {
        assertTrue(service.current(student).isEmpty());
        CvProfile first;
        try (var draft = service.upload(student, pdf)) {
            assertTrue(draft.suggestions().skills().contains("java"));
            assertTrue(service.current(student).isEmpty());
            first = service.confirm(student, draft, qualified());
        }
        assertEquals(List.of("java", "sql", "communication"), first.qualifications().skills());
        assertEquals("computer science", first.qualifications().field());
        assertEquals(List.of("demo certificate"), first.qualifications().certifications());
        assertEquals(YearMonth.of(2026, 11), first.qualifications().experience().get(1).end());
        try (var replacement = service.upload(student, docx)) {
            assertEquals(first.id(), service.current(student).orElseThrow().id());
            assertFalse(replacement.suggestions().projects().isEmpty());
            service.confirm(student, replacement, Qualifications.empty());
        }
        var restarted = new CvService(identity, new JdbcCvs(database), documents, Clock.offset(clock, java.time.Duration.ofDays(90)));
        assertEquals(2, restarted.history(student).size());
        assertTrue(restarted.current(student).orElseThrow().qualifications().skills().isEmpty());
        CvProfile historical = restarted.review(student, first.id());
        assertEquals(first.qualifications(), historical.qualifications());
        assertArrayEquals(Files.readAllBytes(pdf), restarted.download(student, first.id()));
        assertEquals(first.extractedText(), historical.extractedText());
        // A new review advances an ongoing role's end; the previously saved version stays frozen.
        try (var edit = restarted.edit(student)) {
            CvProfile updated = restarted.confirm(student, edit, first.qualifications());
            assertEquals(YearMonth.now(Clock.offset(clock, java.time.Duration.ofDays(90))), updated.qualifications().experience().get(1).end());
            assertEquals(YearMonth.of(2026, 11), restarted.review(student, first.id()).qualifications().experience().get(1).end());
        }
    }

    @Test void cancelParseFailureStorageFailureAndDatabaseRollbackLeavePreviousReviewUsable() throws Exception {
        CvProfile first;
        try (var draft = service.upload(student, pdf)) { first = service.confirm(student, draft, qualified()); }
        try (var cancelled = service.upload(student, docx)) { assertNotNull(cancelled.suggestions()); }
        assertThrows(DocumentFailure.class, () -> service.upload(student, Path.of("src/test/resources/documents/textless.pdf")));
        assertThrows(DocumentFailure.class, () -> service.upload(student, root.resolve("missing.pdf")));
        try (var failed = service.upload(student, docx)) {
            failCommit = true;
            assertThrows(UserError.class, () -> service.confirm(student, failed, qualified()));
            failCommit = false;
        }
        assertEquals(first.id(), service.current(student).orElseThrow().id());
        assertEquals(1, service.history(student).size());
        assertArrayEquals(Files.readAllBytes(pdf), service.download(student, first.id()));
        try (var files = Files.list(root)) { assertEquals(1, files.filter(p -> p.toString().endsWith(".pdf") || p.toString().endsWith(".docx")).count()); }
    }

    @Test void permissionsRejectOtherStudentsCompaniesAdminsAndDisabledSessions() throws Exception {
        CvProfile saved;
        try (var draft = service.upload(student, pdf)) {
            assertThrows(UserError.class, () -> service.confirm(other, draft, qualified()));
            saved = service.confirm(student, draft, qualified());
        }
        assertTrue(service.history(other).isEmpty());
        assertThrows(UserError.class, () -> service.download(other, saved.id()));
        assertThrows(UserError.class, () -> service.review(other, saved.id()));
        for (UUID forbidden : List.of(company, admin)) {
            assertThrows(UserError.class, () -> service.upload(forbidden, pdf));
            assertThrows(UserError.class, () -> service.current(forbidden));
            assertThrows(UserError.class, () -> service.download(forbidden, saved.id()));
        }
        try (var draft = service.edit(student)) {
            try (var connection = database.connect(); var update = connection.prepareStatement("UPDATE users SET active=FALSE WHERE id=?")) {
                update.setLong(1, saved.studentId()); update.executeUpdate();
            }
            assertThrows(UserError.class, () -> service.confirm(student, draft, qualified()));
            assertThrows(UserError.class, () -> service.download(student, saved.id()));
        }
    }

    @Test void editsCreateVersionsAndStaleReviewsCannotOverwriteNewerQualifications() {
        CvProfile original;
        try (var draft = service.upload(student, pdf)) { original = service.confirm(student, draft, qualified()); }
        try (var firstEdit = service.edit(student); var staleEdit = service.edit(student)) {
            CvProfile updated = service.confirm(student, firstEdit, Qualifications.empty());
            assertEquals(original.documentId(), updated.documentId());
            assertNotEquals(original.id(), updated.id());
            assertThrows(UserError.class, () -> service.confirm(student, staleEdit, qualified()));
            assertEquals(updated.id(), service.current(student).orElseThrow().id());
        }
        assertEquals(2, service.history(student).size());
    }

    @Test void invalidReviewsStayPendingAndMissingSavedBytesBlockFurtherReviewWithoutRemovingHistory() throws Exception {
        try (var draft = service.upload(student, pdf)) {
            List<Qualifications> invalid = List.of(
                    new Qualifications(List.of("unknown"), null, "", List.of(), List.of(), List.of()),
                    new Qualifications(List.of("java", "Java"), null, "", List.of(), List.of(), List.of()),
                    new Qualifications(List.of(), null, "Computer Science", List.of(), List.of(), List.of()),
                    new Qualifications(List.of(), null, "", List.of(), List.of(), List.of("Demo  cert", "demo cert")),
                    new Qualifications(List.of(), null, "", List.of(), List.of(new Experience("Future", YearMonth.of(2027, 1), YearMonth.of(2027, 2), false, List.of())), List.of()),
                    new Qualifications(List.of(), null, "", List.of(), List.of(new Experience("Reversed", YearMonth.of(2025, 6), YearMonth.of(2025, 1), false, List.of())), List.of()));
            for (Qualifications input : invalid) { assertThrows(UserError.class, () -> service.confirm(student, draft, input)); }
            assertTrue(service.current(student).isEmpty());
            CvProfile saved = service.confirm(student, draft, qualified());
            Files.delete(root.resolve(saved.documentId() + ".pdf"));
            assertThrows(DocumentFailure.class, () -> service.edit(student));
            assertThrows(DocumentFailure.class, () -> service.download(student, saved.id()));
            assertEquals(saved.id(), service.current(student).orElseThrow().id());
            assertEquals(1, service.history(student).size());
        }
    }

    @AfterEach void cleanup() throws Exception {
        failCommit = false;
        if (documents != null) { documents.close(); }
        try (var connection = database.connect()) {
            for (long account : accounts) {
                var documentIds = new ArrayList<String>();
                try (var query = connection.prepareStatement("SELECT DISTINCT document_id FROM cv_versions WHERE student_id=?")) {
                    query.setLong(1, account); try (var rows = query.executeQuery()) { while (rows.next()) { documentIds.add(rows.getString(1)); } }
                }
                execute(connection, "DELETE FROM cv_current WHERE student_id=?", account);
                for (String table : List.of("cv_evidence_skills", "cv_evidence", "cv_skills", "cv_certifications")) {
                    execute(connection, "DELETE c FROM " + table + " c JOIN cv_versions v ON v.id=c.version_id WHERE v.student_id=?", account);
                }
                execute(connection, "DELETE FROM cv_versions WHERE student_id=?", account);
                for (String id : documentIds) { execute(connection, "DELETE FROM managed_documents WHERE id=?", id); }
                execute(connection, "DELETE FROM students WHERE user_id=?", account);
                execute(connection, "DELETE FROM companies WHERE user_id=?", account);
                execute(connection, "DELETE FROM users WHERE id=?", account);
            }
            for (String id : insertedSkills) { execute(connection, "DELETE FROM skills WHERE id=?", id); }
        }
    }

    private static void execute(java.sql.Connection connection, String sql, Object value) throws Exception {
        try (var statement = connection.prepareStatement(sql)) { statement.setObject(1, value); statement.executeUpdate(); }
    }
}
