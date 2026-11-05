package smarthire;

import java.util.Arrays;
import smarthire.identity.Passwords;
import smarthire.identity.Role;
import smarthire.storage.JdbcAccounts;

/** Explicit local demo provisioning; never runs as part of ordinary application startup. */
public final class DemoSeed {
    private DemoSeed() { }

    public static void provision(JdbcAccounts accounts) {
        for (Role role : Role.values()) {
            String email = role.name().toLowerCase(java.util.Locale.ROOT) + "@smarthire.example";
            if (accounts.findByEmail(email).isEmpty()) {
                char[] password = "SmartHireDemo!21".toCharArray();
                try {
                    accounts.create(email, Passwords.hash(password), role, switch (role) {
                        case STUDENT -> "Arafat Hasan";
                        case COMPANY -> "Dhaka Software Demo";
                        case ADMIN -> "Farhana Rahman";
                    });
                } finally {
                    Arrays.fill(password, '\0');
                }
            }
        }
    }
}
