package smarthire.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;
import smarthire.identity.IdentityService;
import smarthire.identity.Profile;
import smarthire.identity.Role;
import smarthire.identity.UserError;

public final class SmartHireWindow extends JFrame {
    private final JPanel content = new JPanel(new BorderLayout(24, 24));
    private final JTextArea status = new JTextArea("Starting SmartHire…", 3, 30);
    private IdentityService identity;
    private smarthire.jobs.JobService jobs;
    private smarthire.cv.CvService cvs;
    private smarthire.matching.MatchService matches;
    private smarthire.applications.ApplicationService applications;
    private smarthire.workspace.WorkspaceService workspace;
    private smarthire.cv.CvApprovalService cvApprovals;
    private UUID session;
    private boolean busy;

    public SmartHireWindow() {
        super("SmartHire • Local hiring demo");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        content.setBorder(BorderFactory.createEmptyBorder(24, 28, 24, 28));
        add(content, BorderLayout.CENTER);
        status.setEditable(false);
        status.setFocusable(false);
        status.setLineWrap(true);
        status.setWrapStyleWord(true);
        status.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        status.setBorder(BorderFactory.createEmptyBorder(10, 28, 16, 28));
        add(status, BorderLayout.SOUTH);
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        setSize(Math.min(1040, screen.width - 80), Math.min(760, screen.height - 80));
        setMinimumSize(new Dimension(Math.min(620, getWidth()), Math.min(460, getHeight())));
        setLocationRelativeTo(null);
    }

    public void connect(Callable<IdentityService> connection) {
        connect(connection, ignored -> null);
    }

    public void connect(Callable<IdentityService> connection, java.util.function.Function<IdentityService, smarthire.jobs.JobService> jobFactory) {
        connect(connection, jobFactory, ignored -> null);
    }

    public void connect(Callable<IdentityService> connection, java.util.function.Function<IdentityService, smarthire.jobs.JobService> jobFactory,
            java.util.function.Function<IdentityService, smarthire.cv.CvService> cvFactory) {
        connect(connection, jobFactory, cvFactory, ignored -> null);
    }

    public void connect(Callable<IdentityService> connection, java.util.function.Function<IdentityService, smarthire.jobs.JobService> jobFactory,
            java.util.function.Function<IdentityService, smarthire.cv.CvService> cvFactory,
            java.util.function.Function<IdentityService, smarthire.applications.ApplicationService> applicationFactory) {
        connect(connection, jobFactory, cvFactory, applicationFactory, ignored -> null);
    }

    public void connect(Callable<IdentityService> connection, java.util.function.Function<IdentityService, smarthire.jobs.JobService> jobFactory,
            java.util.function.Function<IdentityService, smarthire.cv.CvService> cvFactory,
            java.util.function.Function<IdentityService, smarthire.applications.ApplicationService> applicationFactory,
            java.util.function.Function<IdentityService, smarthire.workspace.WorkspaceService> workspaceFactory) {
        connect(connection, jobFactory, cvFactory, applicationFactory, workspaceFactory, ignored -> null);
    }

    public void connect(Callable<IdentityService> connection, java.util.function.Function<IdentityService, smarthire.jobs.JobService> jobFactory,
            java.util.function.Function<IdentityService, smarthire.cv.CvService> cvFactory,
            java.util.function.Function<IdentityService, smarthire.applications.ApplicationService> applicationFactory,
            java.util.function.Function<IdentityService, smarthire.workspace.WorkspaceService> workspaceFactory,
            java.util.function.Function<IdentityService, smarthire.cv.CvApprovalService> approvalFactory) {
        showBody("Welcome to SmartHire", new JLabel("Connecting to your local workspace…"));
        run(() -> { var service = connection.call(); return new Services(service, jobFactory.apply(service), cvFactory.apply(service), applicationFactory.apply(service), workspaceFactory.apply(service), approvalFactory.apply(service)); }, connected -> {
            identity = connected.identity(); jobs = connected.jobs(); cvs = connected.cvs();
            matches = jobs == null || cvs == null ? null : new smarthire.matching.MatchService(identity, cvs, jobs);
            applications = connected.applications();
            workspace = connected.workspace();
            cvApprovals = connected.cvApprovals();
            login();
        }, () -> {
            JButton retry = new JButton("Retry connection");
            retry.addActionListener(event -> connect(connection, jobFactory, cvFactory, applicationFactory, workspaceFactory, approvalFactory));
            showBody("Database unavailable", retry);
        });
    }

    private void login() {
        JPanel form = form();
        JTextField email = new JTextField();
        JPasswordField password = new JPasswordField();
        field(form, "Email", email);
        field(form, "Password", password);
        JButton signIn = new JButton("Sign in");
        JButton register = new JButton("Create an account");
        form.add(signIn);
        form.add(Box.createVerticalStrut(12));
        form.add(register);
        signIn.addActionListener(event -> {
            String address = email.getText();
            char[] secret = password.getPassword();
            run(() -> {
                try {
                    UUID token = identity.login(address, secret);
                    try {
                        return new LoginResult(token, identity.profile(token));
                    } catch (RuntimeException exception) {
                        identity.logout(token);
                        throw exception;
                    }
                } finally {
                    Arrays.fill(secret, '\0');
                }
            }, result -> {
                password.setText("");
                session = result.token();
                home(result.profile());
            });
        });
        register.addActionListener(event -> register());
        showBody("SmartHire · Sign in", form);
        getRootPane().setDefaultButton(signIn);
        status.setText("A local workspace for students, companies, and administrators.");
    }

    private void register() {
        JPanel form = form();
        JTextField name = new JTextField();
        JTextField email = new JTextField();
        JPasswordField password = new JPasswordField();
        JComboBox<Role> role = new JComboBox<>(new Role[]{Role.STUDENT, Role.COMPANY});
        field(form, "Account type", role);
        field(form, "Your name / company name", name);
        field(form, "Email", email);
        field(form, "Password (10–128 characters)", password);
        JButton create = new JButton("Create account");
        JButton back = new JButton("Back to sign in");
        form.add(create);
        form.add(Box.createVerticalStrut(12));
        form.add(back);
        create.addActionListener(event -> {
            String address = email.getText();
            String displayName = name.getText();
            Role selected = (Role) role.getSelectedItem();
            char[] secret = password.getPassword();
            run(() -> {
                try {
                    return identity.register(address, secret, selected, displayName);
                } finally {
                    Arrays.fill(secret, '\0');
                }
            }, created -> {
                password.setText("");
                login();
                status.setText("Account created for " + created.email() + ". You can now sign in.");
            });
        });
        back.addActionListener(event -> login());
        showBody("Create your SmartHire account", form);
        getRootPane().setDefaultButton(create);
        status.setText("Students and companies can register. Admin accounts are provisioned separately.");
    }

    private void home(Profile profile) {
        JPanel panel = new JPanel(new BorderLayout(24, 24));
        JPanel navigation = new JPanel(new GridLayout(0, 1, 0, 12));
        JButton dashboard = new JButton("Overview");
        JButton edit = new JButton("My profile");
        JButton logout = new JButton("Sign out");
        navigation.add(dashboard);
        navigation.add(edit);
        if (cvs != null && profile.role() == Role.STUDENT) {
            JButton cv = new JButton("My CV");
            navigation.add(cv);
            cv.addActionListener(event -> showBody("My CV and qualifications", new CvPanel(cvs, session, () -> run(() -> identity.profile(session), this::home))));
        }
        if (jobs != null) {
            JButton postings = new JButton(profile.role() == Role.STUDENT ? "Find jobs" : "Jobs and administration");
            navigation.add(postings);
            postings.addActionListener(event -> showBody("Job workspace", new JobsPanel(jobs, session, profile.role(), () -> run(() -> identity.profile(session), this::home), 0, applications)));
        }
        if (matches != null && profile.role() == Role.STUDENT) {
            JButton recommendations = new JButton("Job matches");
            navigation.add(recommendations);
            recommendations.addActionListener(event -> showMatches());
        }
        if (applications != null && profile.role() != Role.ADMIN) {
            JButton submissions = new JButton(profile.role() == Role.STUDENT ? "My applications" : "Applicants");
            navigation.add(submissions);
            submissions.addActionListener(event -> showBody(submissions.getText(), new ApplicationsPanel(applications, session, profile.role(),
                    () -> run(() -> identity.profile(session), this::home))));
        }
        if (workspace != null) {
            var inbox = new JButton("Notifications"); navigation.add(inbox);
            inbox.addActionListener(e -> showBody("Notifications", new WorkspacePanel(workspace, session, "notices", () -> run(() -> identity.profile(session), this::home))));
            if (profile.role() == Role.ADMIN) {
                var accounts = new JButton("Accounts"); var monitoring = new JButton("Application monitoring");
                navigation.add(accounts); navigation.add(monitoring);
                accounts.addActionListener(e -> showBody("Accounts", new WorkspacePanel(workspace, session, "accounts", () -> run(() -> identity.profile(session), this::home))));
                monitoring.addActionListener(e -> showBody("Application monitoring", new WorkspacePanel(workspace, session, "monitor", () -> run(() -> identity.profile(session), this::home))));
            }
        }
        if (cvApprovals != null && profile.role() == Role.ADMIN) {
            var reviewCvs = new JButton("CV review"); navigation.add(reviewCvs);
            reviewCvs.addActionListener(e -> showBody("Student CV review", new CvApprovalPanel(cvApprovals, session, () -> run(() -> identity.profile(session), this::home))));
        }
        navigation.add(logout);
        JPanel sidebar = new JPanel(new BorderLayout());
        sidebar.add(navigation, BorderLayout.NORTH);
        panel.add(sidebar, BorderLayout.WEST);
        JTextArea welcome = new JTextArea(switch (profile.role()) {
            case STUDENT -> "Welcome, " + profile.name() + "\n\nStudent workspace\n\nKeep your contact details and university information up to date in My profile.";
            case COMPANY -> "Welcome, " + profile.name() + "\n\nCompany workspace\n\nIntroduce your organization and update its contact details in My profile.";
            case ADMIN -> "Welcome, " + profile.name() + "\n\nAdministration workspace\n\nYour administrator account is ready. Update your contact details in My profile.";
        });
        welcome.setEditable(false);
        welcome.setLineWrap(true);
        welcome.setWrapStyleWord(true);
        welcome.setFont(new Font("Segoe UI", Font.PLAIN, 18));
        panel.add(welcome, BorderLayout.CENTER);
        if (workspace != null) {
            UUID currentSession = session;
            new SwingWorker<smarthire.workspace.WorkspaceRepository.Dashboard, Void>() {
                @Override protected smarthire.workspace.WorkspaceRepository.Dashboard doInBackground() { return workspace.dashboard(currentSession); }
                @Override protected void done() {
                    if (!currentSession.equals(session) || !panel.isShowing()) { return; }
                    try { var result = get(); panel.remove(welcome); panel.add(WorkspacePanel.dashboard(result), BorderLayout.CENTER); panel.revalidate(); panel.repaint(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    catch (ExecutionException e) { welcome.setText(e.getCause() instanceof UserError ? e.getCause().getMessage() : "Could not load overview. Select Overview to retry."); }
                }
            }.execute();
        }
        dashboard.addActionListener(event -> run(() -> identity.requireRole(session, profile.role()), this::home));
        edit.addActionListener(event -> run(() -> identity.profile(session), this::editProfile));
        logout.addActionListener(event -> {
            identity.logout(session);
            session = null;
            login();
        });
        showBody(switch (profile.role()) {
            case STUDENT -> "Student home";
            case COMPANY -> "Company home";
            case ADMIN -> "Admin home";
        }, panel);
        getRootPane().setDefaultButton(null);
        status.setText("Signed in as " + profile.email());
    }

    private void editProfile(Profile profile) {
        JPanel form = form();
        JTextField name = new JTextField(profile.name());
        JTextField phone = new JTextField(profile.phone());
        JTextField affiliation = new JTextField(profile.affiliation());
        JTextArea about = new JTextArea(profile.about(), 5, 30);
        about.setLineWrap(true);
        about.setWrapStyleWord(true);
        field(form, "Name", name);
        field(form, "Phone (optional)", phone);
        field(form, profile.role() == Role.STUDENT ? "University / institution (optional)" : "Organization (optional)", affiliation);
        field(form, "About (optional, up to 2,000 characters)", new JScrollPane(about));
        JButton save = new JButton("Save profile");
        JButton back = new JButton("Back to overview");
        form.add(save);
        form.add(Box.createVerticalStrut(12));
        form.add(back);
        save.addActionListener(event -> {
            String newName = name.getText();
            String newPhone = phone.getText();
            String newAffiliation = affiliation.getText();
            String newAbout = about.getText();
            run(() -> identity.saveProfile(session, newName, newPhone, newAffiliation, newAbout), saved -> {
                editProfile(saved);
                status.setText("Profile saved.");
            });
        });
        back.addActionListener(event -> run(() -> identity.profile(session), this::home));
        showBody("My profile", form);
        status.setText("Signed in as " + profile.email());
        getRootPane().setDefaultButton(save);
    }

    private void showMatches() {
        showBody("Job matches", new MatchesPanel(matches, session,
                () -> run(() -> identity.profile(session), this::home),
                id -> showBody("Job details", new JobsPanel(jobs, session, Role.STUDENT, this::showMatches, id, applications))));
    }

    private void showBody(String title, Component body) {
        content.removeAll();
        JLabel heading = new JLabel(title);
        heading.setFont(new Font("Segoe UI", Font.BOLD, 26));
        content.add(heading, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(body);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        content.add(scroll, BorderLayout.CENTER);
        content.revalidate();
        content.repaint();
    }

    private static JPanel form() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        return panel;
    }

    private static void field(JPanel panel, String label, javax.swing.JComponent input) {
        JLabel caption = new JLabel(label);
        caption.setLabelFor(input);
        caption.setAlignmentX(Component.LEFT_ALIGNMENT);
        input.setAlignmentX(Component.LEFT_ALIGNMENT);
        input.setMaximumSize(new Dimension(Integer.MAX_VALUE, input instanceof JScrollPane ? 140 : 36));
        panel.add(caption);
        panel.add(Box.createVerticalStrut(6));
        panel.add(input);
        panel.add(Box.createVerticalStrut(18));
    }

    private <T> void run(Callable<T> task, Consumer<T> success) {
        run(task, success, () -> { });
    }

    private <T> void run(Callable<T> task, Consumer<T> success, Runnable failure) {
        if (busy) {
            return;
        }
        busy = true;
        enabled(content, false);
        status.setForeground(Color.DARK_GRAY);
        status.setText("Working…");
        new SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() throws Exception {
                return task.call();
            }

            @Override
            protected void done() {
                busy = false;
                enabled(content, true);
                try {
                    T result = get();
                    status.setText("Ready");
                    success.accept(result);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    status.setText("The operation was interrupted. Try again.");
                } catch (ExecutionException exception) {
                    failure.run();
                    status.setForeground(new Color(160, 35, 35));
                    Throwable cause = exception.getCause();
                    status.setText(cause instanceof UserError ? cause.getMessage()
                            : "The operation could not be completed. Please restart SmartHire and try again.");
                }
            }
        }.execute();
    }

    private static void enabled(Component component, boolean value) {
        component.setEnabled(value);
        if (component instanceof java.awt.Container container) {
            for (Component child : container.getComponents()) {
                enabled(child, value);
            }
        }
    }

    private record Services(IdentityService identity, smarthire.jobs.JobService jobs, smarthire.cv.CvService cvs,
            smarthire.applications.ApplicationService applications, smarthire.workspace.WorkspaceService workspace, smarthire.cv.CvApprovalService cvApprovals) { }

    private record LoginResult(UUID token, Profile profile) { }
}
