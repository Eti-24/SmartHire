package smarthire.identity;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IdentityTest {
    private MemoryAccounts accounts;
    private IdentityService service;

    @BeforeEach
    void setup() {
        accounts = new MemoryAccounts();
        service = new IdentityService(accounts);
    }

    @Test
    void registrationNormalizesEmailAndNeverStoresPlaintextPassword() {
        Profile profile = service.register("  Student@Example.com ", secret(), Role.STUDENT, " Avery ");
        Account stored = accounts.findById(profile.id()).orElseThrow();
        assertEquals("student@example.com", stored.email());
        assertEquals("Avery", stored.name());
        assertNotEquals(new String(secret()), stored.passwordHash());
        assertTrue(Passwords.matches(secret(), stored.passwordHash()));
        assertFalse(Passwords.matches("incorrect".toCharArray(), stored.passwordHash()));
        assertThrows(UserError.class, () -> service.register("STUDENT@example.com", secret(), Role.COMPANY, "Other"));
    }

    @Test
    void refusesAdminRegistrationAndInvalidFields() {
        assertThrows(UserError.class, () -> service.register("admin@example.com", secret(), Role.ADMIN, "Admin"));
        assertThrows(UserError.class, () -> service.register("bad", secret(), Role.STUDENT, "Name"));
        assertThrows(UserError.class, () -> service.register("a@example.com", "short".toCharArray(), Role.STUDENT, "Name"));
        assertThrows(UserError.class, () -> service.register("a@example.com", secret(), Role.STUDENT, " "));
        assertTrue(accounts.rows.isEmpty());
    }

    @Test
    void roleChecksAndLogoutAreEnforcedByService() {
        service.register("a@example.com", secret(), Role.STUDENT, "Avery");
        assertThrows(UserError.class, () -> service.login("a@example.com", "wrong".toCharArray()));
        UUID token = service.login("A@EXAMPLE.COM", secret());
        assertEquals(Role.STUDENT, service.requireRole(token, Role.STUDENT).role());
        assertThrows(UserError.class, () -> service.requireRole(token, Role.ADMIN));
        assertThrows(UserError.class, () -> service.profile(UUID.randomUUID()));
        service.logout(token);
        assertThrows(UserError.class, () -> service.profile(token));
        assertThrows(UserError.class, () -> service.saveProfile(token, "Changed", "", "", ""));
    }

    @Test
    void profileUpdatesOnlyTheSignedInAccountAndSurvivesNewService() {
        Profile first = service.register("first@example.com", secret(), Role.STUDENT, "First");
        Profile second = service.register("second@example.com", secret(), Role.COMPANY, "Second");
        UUID token = service.login(first.email(), secret());
        service.saveProfile(token, "Updated", "01234", "Demo University", "Fictional profile");
        IdentityService restarted = new IdentityService(accounts);
        Profile saved = restarted.profile(restarted.login(first.email(), secret()));
        assertEquals("Updated", saved.name());
        assertEquals("Demo University", saved.affiliation());
        assertEquals("Second", accounts.findById(second.id()).orElseThrow().name());
        assertThrows(UserError.class, () -> restarted.profile(token));
    }

    @Test
    void disabledAccountsCannotUseExistingSessions() {
        Profile profile = service.register("a@example.com", secret(), Role.COMPANY, "Company");
        UUID token = service.login(profile.email(), secret());
        Account old = accounts.rows.get(profile.id());
        accounts.rows.put(old.id(), new Account(old.id(), old.email(), old.passwordHash(), old.role(), false,
                old.name(), old.phone(), old.affiliation(), old.about()));
        assertThrows(UserError.class, () -> service.saveProfile(token, "Changed", "", "", ""));
        assertThrows(UserError.class, () -> service.login(profile.email(), secret()));
    }

    @Test
    void provisionedAdminCanSignInButCannotUseAnotherRole() {
        accounts.create("admin@example.com", Passwords.hash(secret()), Role.ADMIN, "Admin");
        UUID token = service.login("admin@example.com", secret());
        assertEquals(Role.ADMIN, service.requireRole(token, Role.ADMIN).role());
        assertThrows(UserError.class, () -> service.requireRole(token, Role.COMPANY));
        service.logout(token);
        assertThrows(UserError.class, () -> service.requireRole(token, Role.ADMIN));
    }

    @Test
    void identicalPasswordsHaveDifferentSalts() {
        String first = Passwords.hash(secret());
        String second = Passwords.hash(secret());
        assertNotEquals(first, second);
        assertTrue(Passwords.matches(secret(), first));
        assertTrue(Passwords.matches(secret(), second));
        assertFalse(Passwords.matches(secret(), "invalid"));
    }

    private static char[] secret() {
        return "Test-password!21".toCharArray();
    }

    private static final class MemoryAccounts implements AccountRepository {
        private final Map<Long, Account> rows = new HashMap<>();

        public Optional<Account> findByEmail(String email) {
            return rows.values().stream().filter(account -> account.email().equals(email)).findFirst();
        }

        public Optional<Account> findById(long id) {
            return Optional.ofNullable(rows.get(id));
        }

        public Account create(String email, String hash, Role role, String name) {
            if (findByEmail(email).isPresent()) {
                throw new UserError("Duplicate email");
            }
            Account account = new Account(rows.size() + 1L, email, hash, role, true, name, "", "", "");
            rows.put(account.id(), account);
            return account;
        }

        public void updateProfile(long id, String name, String phone, String affiliation, String about) {
            Account old = rows.get(id);
            rows.put(id, new Account(id, old.email(), old.passwordHash(), old.role(), old.active(), name, phone, affiliation, about));
        }
    }
}
