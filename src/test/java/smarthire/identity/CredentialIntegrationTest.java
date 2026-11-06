package smarthire.identity;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import smarthire.WorkflowFixture;
import smarthire.storage.JdbcAccounts;
import smarthire.storage.JdbcWorkspace;
import smarthire.workspace.AdminData;
import smarthire.workspace.WorkspaceService;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "smarthire.integration", matches = "true")
class CredentialIntegrationTest {
    @TempDir Path root;
    private static final String INITIAL = "Workflow-test!21";
    private static final String CHANGED = "Changed-test!22";
    private static final String RECOVERED = "Recovered-test!23";

    @Test void recoveryRotationConsumptionAndPasswordChangeInvalidateSessionsWithoutExportingSecrets() throws Exception {
        try (var f = new WorkflowFixture(root)) {
            var profile = f.identity.profile(f.student);
            var other = new IdentityService(new JdbcAccounts(f.database));
            var oldSession = other.login(profile.email(), INITIAL.toCharArray());
            assertThrows(UserError.class, () -> change(f, "wrong", CHANGED));
            change(f, INITIAL, CHANGED);
            assertThrows(UserError.class, () -> f.identity.profile(f.student));
            assertThrows(UserError.class, () -> other.profile(oldSession));
            assertThrows(UserError.class, () -> other.login(profile.email(), INITIAL.toCharArray()));
            var current = f.identity.login(profile.email(), CHANGED.toCharArray());
            var stale = f.identity.issueRecoveryCodes(current, CHANGED.toCharArray());
            var codes = f.identity.issueRecoveryCodes(current, CHANGED.toCharArray());
            assertEquals(6, codes.stream().distinct().count());
            assertThrows(UserError.class, () -> recover(f.identity, profile.email(), stale.getFirst()));
            recover(f.identity, profile.email(), codes.getFirst());
            assertThrows(UserError.class, () -> recover(f.identity, profile.email(), codes.getFirst()));
            assertThrows(UserError.class, () -> recover(f.identity, profile.email(), codes.getLast()));
            assertThrows(UserError.class, () -> f.identity.profile(current));
            var active = other.login(profile.email(), RECOVERED.toCharArray());
            var more = other.issueRecoveryCodes(active, RECOVERED.toCharArray());
            var workspace = new WorkspaceService(f.identity, new JdbcWorkspace(f.database), f.documents);
            var target = new AdminData.Target(AdminData.Kind.STUDENT, Long.toString(profile.id()));
            var file = root.resolve("archive.zip");
            workspace.exportData(f.admin, target, file);
            try (var archive = new ZipFile(file.toFile())) {
                assertNull(archive.getEntry("account_recovery_codes.txt"));
                String account = new String(archive.getInputStream(archive.getEntry("account.txt")).readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
                assertFalse(account.contains("password_hash"));
                assertFalse(account.contains(more.getFirst()));
            }
            workspace.setActive(f.admin, profile.id(), false);
            assertThrows(UserError.class, () -> recover(other, profile.email(), more.getFirst()));
            workspace.eraseData(f.admin, workspace.eraseImpact(f.admin, target));
            assertThrows(UserError.class, () -> recover(other, profile.email(), more.getFirst()));
        }
    }

    @Test void concurrentChangesCannotBothCommitUsingTheSameCredential() throws Exception {
        try (var f = new WorkflowFixture(root); var pool = Executors.newFixedThreadPool(2)) {
            var profile = f.identity.profile(f.student);
            var other = new IdentityService(new JdbcAccounts(f.database));
            var second = other.login(profile.email(), INITIAL.toCharArray());
            var start = new CountDownLatch(1);
            var first = pool.submit(() -> { start.await(); return attempt(() -> change(f, INITIAL, CHANGED)); });
            var competing = pool.submit(() -> {
                start.await();
                return attempt(() -> other.changePassword(second, INITIAL.toCharArray(), RECOVERED.toCharArray(), RECOVERED.toCharArray()));
            });
            start.countDown();
            assertNotEquals(first.get(15, TimeUnit.SECONDS), competing.get(15, TimeUnit.SECONDS));
        }
    }

    @Test void recoveryAttemptsAreBoundedAndUnknownAccountsReceiveTheSameError() throws Exception {
        try (var f = new WorkflowFixture(root)) {
            var profile = f.identity.profile(f.student);
            var valid = f.identity.issueRecoveryCodes(f.student, INITIAL.toCharArray()).getFirst();
            String unknown = assertThrows(UserError.class, () -> recover(f.identity, "unknown@workflow.example", valid)).getMessage();
            for (int attempt = 0; attempt < 5; attempt++) {
                assertEquals(unknown, assertThrows(UserError.class, () -> recover(f.identity, profile.email(), "wrong")).getMessage());
            }
            assertEquals(unknown, assertThrows(UserError.class, () -> recover(f.identity, profile.email(), valid)).getMessage());
            assertNotNull(f.identity.login(profile.email(), INITIAL.toCharArray()));
        }
    }

    private static boolean attempt(Runnable action) {
        try { action.run(); return true; }
        catch (UserError e) { return false; }
    }

    private static void change(WorkflowFixture f, String old, String next) {
        f.identity.changePassword(f.student, old.toCharArray(), next.toCharArray(), next.toCharArray());
    }

    private static void recover(IdentityService identity, String email, String code) {
        identity.recoverPassword(email, code.toCharArray(), RECOVERED.toCharArray(), RECOVERED.toCharArray());
    }
}
