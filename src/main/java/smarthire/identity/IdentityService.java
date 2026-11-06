package smarthire.identity;

import java.util.Locale;
import java.util.List;
import java.util.Arrays;
import java.util.HashMap;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

public final class IdentityService {
    private final AccountRepository accounts;
    private final ConcurrentHashMap<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final HashMap<String, Integer> recoveryAttempts = new HashMap<>();
    private Instant recoveryWindowEnd = Instant.EPOCH;
    private int recoveryAttemptTotal;
    private record Session(long id, long credentialVersion) {}

    public IdentityService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    public Profile register(String email, char[] password, Role role, String name) {
        if (role != Role.STUDENT && role != Role.COMPANY) {
            throw new UserError("Choose Student or Company. Admin accounts are provisioned separately.");
        }
        String normalized = email(email);
        String validName = text(name, "Name", 120, true);
        validatePassword(password);
        return Profile.from(accounts.create(normalized, Passwords.hash(password), role, validName));
    }

    public UUID login(String email, char[] password) {
        if (password == null || password.length > 128) {
            throw new UserError("Email or password is incorrect.");
        }
        Account account = accounts.findByEmail(email(email))
                .orElseThrow(() -> new UserError("Email or password is incorrect."));
        if (!Passwords.matches(password, account.passwordHash()) || !account.active()) {
            throw new UserError("Email or password is incorrect, or this account is disabled.");
        }
        UUID session = UUID.randomUUID();
        sessions.put(session, new Session(account.id(), account.credentialVersion()));
        return session;
    }

    public Profile profile(UUID session) {
        return Profile.from(authenticated(session));
    }

    public Profile requireRole(UUID session, Role role) {
        Account account = authenticated(session);
        if (account.role() != role) {
            throw new UserError("Your account does not have access to this area.");
        }
        return Profile.from(account);
    }

    public Profile saveProfile(UUID session, String name, String phone, String affiliation, String about) {
        Account account = authenticated(session);
        accounts.updateProfile(account.id(), text(name, "Name", 120, true),
                text(phone, "Phone", 40, false), text(affiliation, "Organization", 180, false),
                text(about, "About", 2000, false));
        return profile(session);
    }

    public void logout(UUID session) {
        if (session != null) {
            sessions.remove(session);
        }
    }

    public void changePassword(UUID session, char[] current, char[] replacement, char[] confirmation) {
        Account account = verifyCurrentPassword(session, current);
        validateReplacement(replacement, confirmation);
        if (!accounts.changePassword(account.id(), account.credentialVersion(), Passwords.hash(replacement))) {
            throw new UserError("Credentials changed or the account became unavailable. Sign in again.");
        }
        logout(session);
    }

    public List<String> issueRecoveryCodes(UUID session, char[] current) {
        Account account = verifyCurrentPassword(session, current);
        List<String> codes = RecoveryCodes.generate();
        var hashes = codes.stream().map(code -> {
            char[] characters = code.toCharArray();
            try { return RecoveryCodes.hash(characters); }
            finally { Arrays.fill(characters, '\0'); }
        }).toList();
        if (!accounts.replaceRecoveryCodes(account.id(), account.credentialVersion(), hashes)) {
            throw new UserError("Credentials changed or the account became unavailable. Sign in again.");
        }
        return codes;
    }

    public void recoverPassword(String email, char[] code, char[] replacement, char[] confirmation) {
        validateReplacement(replacement, confirmation);
        String address;
        try { address = email(email); }
        catch (UserError e) { address = ""; }
        if (!allowRecoveryAttempt(address)
                || !accounts.recoverPassword(address, RecoveryCodes.hash(code), Passwords.hash(replacement))) {
            throw new UserError("Recovery failed. Check your email and code, or wait 15 minutes before trying again.");
        }
        synchronized (this) { recoveryAttempts.remove(address); }
    }

    private synchronized boolean allowRecoveryAttempt(String email) {
        Instant now = Instant.now();
        if (!now.isBefore(recoveryWindowEnd)) {
            recoveryAttempts.clear();
            recoveryAttemptTotal = 0;
            recoveryWindowEnd = now.plusSeconds(900);
        }
        int attempts = recoveryAttempts.getOrDefault(email, 0);
        if (attempts >= 5 || recoveryAttemptTotal >= 30) { return false; }
        recoveryAttempts.put(email, attempts + 1);
        recoveryAttemptTotal++;
        return true;
    }

    private Account verifyCurrentPassword(UUID session, char[] current) {
        Account account = authenticated(session);
        if (current == null || current.length > 128 || !Passwords.matches(current, account.passwordHash())) {
            throw new UserError("The current password is incorrect.");
        }
        return account;
    }

    private static void validateReplacement(char[] password, char[] confirmation) {
        validatePassword(password);
        if (!Arrays.equals(password, confirmation)) { throw new UserError("The new passwords do not match."); }
    }

    private static void validatePassword(char[] password) {
        if (password == null || password.length < 10 || password.length > 128) {
            throw new UserError("Password must contain 10 to 128 characters.");
        }
    }

    private Account authenticated(UUID session) {
        Session loggedIn = session == null ? null : sessions.get(session);
        if (loggedIn == null) {
            throw new UserError("Please sign in again.");
        }
        Account account = accounts.findById(loggedIn.id()).orElseThrow(() -> new UserError("Please sign in again."));
        if (!account.active()) {
            sessions.remove(session);
            throw new UserError("This account is disabled. Please contact the administrator.");
        }
        if (account.credentialVersion() != loggedIn.credentialVersion()) {
            sessions.remove(session);
            throw new UserError("Your credentials changed. Please sign in again.");
        }
        return account;
    }

    private static String email(String value) {
        String normalized = text(value, "Email", 254, true).toLowerCase(Locale.ROOT);
        if (!normalized.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
            throw new UserError("Enter a valid email address.");
        }
        return normalized;
    }

    private static String text(String value, String label, int maximum, boolean required) {
        String trimmed = value == null ? "" : value.strip();
        if ((required && trimmed.isEmpty()) || trimmed.length() > maximum) {
            throw new UserError(label + (required ? " is required and" : "")
                    + " must be at most " + maximum + " characters.");
        }
        return trimmed;
    }
}
