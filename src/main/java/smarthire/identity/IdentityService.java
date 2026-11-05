package smarthire.identity;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class IdentityService {
    private final AccountRepository accounts;
    private final ConcurrentHashMap<UUID, Long> sessions = new ConcurrentHashMap<>();

    public IdentityService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    public Profile register(String email, char[] password, Role role, String name) {
        if (role != Role.STUDENT && role != Role.COMPANY) {
            throw new UserError("Choose Student or Company. Admin accounts are provisioned separately.");
        }
        String normalized = email(email);
        String validName = text(name, "Name", 120, true);
        if (password == null || password.length < 10 || password.length > 128) {
            throw new UserError("Password must contain 10 to 128 characters.");
        }
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
        sessions.put(session, account.id());
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

    private Account authenticated(UUID session) {
        Long id = session == null ? null : sessions.get(session);
        if (id == null) {
            throw new UserError("Please sign in again.");
        }
        Account account = accounts.findById(id).orElseThrow(() -> new UserError("Please sign in again."));
        if (!account.active()) {
            sessions.remove(session);
            throw new UserError("This account is disabled. Please contact the administrator.");
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
