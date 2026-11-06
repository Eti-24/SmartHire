package smarthire;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;
import smarthire.identity.Passwords;
import smarthire.identity.Role;
import smarthire.identity.UserError;
import smarthire.storage.JdbcAccounts;

/** Explicit fictional accounts; never runs as part of ordinary application startup. */
public final class DemoSeed {
    private static final String DOMAIN = "@smarthire.example";
    private static final String LOCAL_PASSWORD = "SmartHireDemo!21";
    static final String SOFTWARE_COMPANY = "company.buriganga" + DOMAIN;
    static final String BUSINESS_COMPANY = "company.padmabridge" + DOMAIN;

    private DemoSeed() { }

    public static void provision(JdbcAccounts accounts) {
        provision(accounts, LOCAL_PASSWORD, LOCAL_PASSWORD);
    }

    static void provisionCloud(JdbcAccounts accounts, Path passwordFile) {
        var passwords = new Properties();
        try (var input = Files.newInputStream(passwordFile)) {
            passwords.load(input);
        } catch (IOException exception) {
            throw new UserError("Create the private aiven-demo-accounts.properties file before seeding Aiven.");
        }
        String demo = passwords.getProperty("demoPassword", "");
        String admin = passwords.getProperty("adminPassword", "");
        if (demo.isBlank() || admin.isBlank() || demo.length() < 16 || admin.length() < 16
                || demo.equals(admin) || LOCAL_PASSWORD.equals(demo) || LOCAL_PASSWORD.equals(admin)) {
            throw new UserError("Use separate private cloud demo and admin passwords of at least 16 characters.");
        }
        provision(accounts, demo, admin);
    }

    private static void provision(JdbcAccounts accounts, String demo, String admin) {
        create(accounts, "admin" + DOMAIN, Role.ADMIN, "Farhana Rahman", admin);
        create(accounts, "student" + DOMAIN, Role.STUDENT, "Arafat Hasan", demo);
        create(accounts, "company" + DOMAIN, Role.COMPANY, "Dhaka Software Demo", demo);
        create(accounts, "student.nusrat" + DOMAIN, Role.STUDENT, "Nusrat Jahan", demo);
        create(accounts, "student.tanvir" + DOMAIN, Role.STUDENT, "Tanvir Ahmed", demo);
        create(accounts, "student.sadia" + DOMAIN, Role.STUDENT, "Sadia Rahman", demo);
        create(accounts, SOFTWARE_COMPANY, Role.COMPANY, "Buriganga Software Demo", demo);
        create(accounts, BUSINESS_COMPANY, Role.COMPANY, "Padma Bridge Services Demo", demo);
    }

    private static void create(JdbcAccounts accounts, String email, Role role, String name, String secret) {
        if (accounts.findByEmail(email).isPresent()) { return; }
        char[] password = secret.toCharArray();
        try {
            accounts.create(email, Passwords.hash(password), role, name);
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}
