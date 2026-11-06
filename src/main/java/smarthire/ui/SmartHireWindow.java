package smarthire.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagLayout;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;
import smarthire.identity.IdentityService;
import smarthire.identity.Profile;
import smarthire.identity.Role;
import smarthire.identity.UserError;

public final class SmartHireWindow extends JFrame {
    static { WorkspaceStyle.install(); }

    private static final BufferedImage LOGO = loadLogo();
    // Isolate the emblem from the wordmark so it remains recognizable at icon sizes.
    private static final BufferedImage EMBLEM = LOGO.getSubimage(402, 94, 602, 602);
    private static final ImageIcon SIGN_IN_LOGO = new ImageIcon(LOGO.getScaledInstance(224, -1, Image.SCALE_SMOOTH),
            "SmartHire — Intelligent CV Analysis & Job Recommendation");
    private static final ImageIcon SIDEBAR_ICON = new ImageIcon(EMBLEM.getScaledInstance(32, 32, Image.SCALE_SMOOTH));

    private final JPanel content = new JPanel(new BorderLayout(0, 12));
    private final JTextArea status = WorkspaceStyle.message("Starting SmartHire…");
    private final JPanel sidebar = new JPanel(new BorderLayout(0, 12));
    private final Map<String, JButton> navigation = new LinkedHashMap<>();
    private JPanel navigationLinks;
    private JLabel account;
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
        setIconImages(List.of(EMBLEM.getScaledInstance(16, 16, Image.SCALE_SMOOTH), SIDEBAR_ICON.getImage(),
                EMBLEM.getScaledInstance(48, 48, Image.SCALE_SMOOTH)));
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        content.setBorder(BorderFactory.createEmptyBorder(16, 18, 12, 18));
        add(content, BorderLayout.CENTER);
        status.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        sidebar.setBackground(WorkspaceStyle.NAVY);
        sidebar.setVisible(false);
        add(sidebar, BorderLayout.WEST);
        var screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        setSize(Math.min(1210, screen.width), Math.min(700, screen.height));
        setMinimumSize(new Dimension(Math.min(920, getWidth()), Math.min(460, getHeight())));
        setLocationRelativeTo(null);
    }

    private static BufferedImage loadLogo() {
        try {
            return ImageIO.read(SmartHireWindow.class.getResource("/ui/smarthire-logo.png"));
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not load the bundled SmartHire logo.", exception);
        }
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
            var actions = WorkspaceStyle.actions();
            WorkspaceStyle.primary(retry);
            actions.add(retry);
            showBody("Database unavailable", actions, false);
        });
    }

    private void login() {
        sidebar.setVisible(false);
        sidebar.removeAll();
        navigation.clear();
        JPanel form = WorkspaceStyle.form();
        JTextField email = new JTextField(30);
        JPasswordField password = new JPasswordField(30);
        form.add(new JLabel(SIGN_IN_LOGO));
        form.add(Box.createVerticalStrut(12));
        field(form, "Email", email);
        field(form, "Password", password);
        JButton signIn = new JButton("Sign in");
        JButton register = new JButton("Create an account");
        var actions = formActions(signIn, register);
        actions.add(WorkspaceStyle.button("Recover with a saved code", () -> credentialForm(null)));
        form.add(actions);
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
        showBody("SmartHire · Sign in", centered(form));
        getRootPane().setDefaultButton(signIn);
        status.setText("A local workspace for students, companies, and administrators.");
    }

    private void register() {
        JPanel form = WorkspaceStyle.form();
        JTextField name = new JTextField(30);
        JTextField email = new JTextField(30);
        JPasswordField password = new JPasswordField(30);
        JComboBox<Role> role = new JComboBox<>(new Role[]{Role.STUDENT, Role.COMPANY});
        field(form, "Account type", role);
        field(form, "Your name / company name", name);
        field(form, "Email", email);
        field(form, "Password (10–128 characters)", password);
        JButton create = new JButton("Create account");
        JButton back = new JButton("Back to sign in");
        form.add(formActions(create, back));
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
        showBody("Create your SmartHire account", centered(form));
        getRootPane().setDefaultButton(create);
        status.setText("Students and companies can register. Admin accounts are provisioned separately.");
    }

    private void home(Profile profile) {
        if (navigation.isEmpty()) {
            buildNavigation(profile.role());
        }
        updateAccount(profile);
        JPanel panel = new JPanel(new BorderLayout(0, 12));
        var greeting = WorkspaceStyle.message("Welcome, " + profile.name());
        greeting.setFont(greeting.getFont().deriveFont(Font.BOLD, 16f));
        panel.add(greeting, BorderLayout.NORTH);
        var loading = WorkspaceStyle.message(workspace == null ? "Use the sidebar to open your workspace." : "Loading workspace activity…");
        panel.add(loading, BorderLayout.CENTER);
        if (workspace != null) {
            UUID currentSession = session;
            new SwingWorker<smarthire.workspace.WorkspaceRepository.Dashboard, Void>() {
                @Override protected smarthire.workspace.WorkspaceRepository.Dashboard doInBackground() { return workspace.dashboard(currentSession); }
                @Override protected void done() {
                    if (!currentSession.equals(session) || !panel.isShowing()) { return; }
                    try {
                        var result = get();
                        panel.remove(loading);
                        panel.add(WorkspacePanel.dashboard(result), BorderLayout.CENTER);
                        panel.revalidate();
                        panel.repaint();
                    }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    catch (ExecutionException e) { loading.setText(e.getCause() instanceof UserError ? e.getCause().getMessage() : "Could not load overview. Select Overview to retry."); }
                }
            }.execute();
        }
        showBody("Overview", panel, false);
    }

    private void updateAccount(Profile profile) {
        account.setText(profile.name());
        account.setToolTipText(profile.name() + " · " + profile.email());
    }

    private void buildNavigation(Role role) {
        sidebar.setPreferredSize(new Dimension(210, 0));
        sidebar.setBorder(BorderFactory.createEmptyBorder(18, 12, 12, 12));
        var brand = new JLabel("SmartHire", SIDEBAR_ICON, SwingConstants.LEADING);
        brand.setIconTextGap(8);
        brand.setFont(brand.getFont().deriveFont(Font.BOLD, 23f));
        brand.setForeground(Color.WHITE);
        var context = new JLabel(WorkspaceStyle.label(role) + " workspace");
        context.setForeground(new Color(187, 207, 225));
        var identityHeader = new JPanel(new BorderLayout(0, 4));
        identityHeader.setOpaque(false);
        identityHeader.add(brand, BorderLayout.NORTH);
        identityHeader.add(context, BorderLayout.SOUTH);
        sidebar.add(identityHeader, BorderLayout.NORTH);

        navigationLinks = new JPanel();
        navigationLinks.setOpaque(false);
        navigationLinks.setLayout(new BoxLayout(navigationLinks, BoxLayout.Y_AXIS));
        var scroll = new JScrollPane(navigationLinks, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(WorkspaceStyle.NAVY);
        sidebar.add(scroll, BorderLayout.CENTER);

        Runnable overview = () -> run(() -> identity.requireRole(session, role), this::home);
        nav("Overview", overview);
        if (jobs != null) {
            String title = switch (role) {
                case STUDENT -> "Find jobs";
                case COMPANY -> "My jobs";
                case ADMIN -> "Jobs and administration";
            };
            nav(title, () -> showBody(title, new JobsPanel(jobs, session, role, null, 0, applications), false));
        }
        if (role == Role.STUDENT) {
            if (matches != null) { nav("Job matches", this::showMatches); }
            if (jobs != null) { nav("Saved jobs", this::showSavedJobs); }
            if (jobs != null) { nav("Saved searches", this::showSavedSearches); }
            if (cvs != null) { nav("My CV", () -> showBody("My CV", new CvPanel(cvs, session, null), false)); }
        }
        if (applications != null && role != Role.ADMIN) {
            String title = role == Role.STUDENT ? "My applications" : "Applicants";
            nav(title, () -> showBody(title, new ApplicationsPanel(applications, session, role), false));
            nav("Interviews", () -> showBody("Interviews", new InterviewsPanel(applications, session, role, null), false));
        }
        if (jobs != null && role != Role.ADMIN) {
            nav("Hiring history", () -> showBody("Hiring history", new HiringHistoryPanel(jobs, session, role,
                    applications == null ? null : id -> showBody("Applicants", new ApplicationsPanel(applications, session, role, id), false)), false));
        }
        if (role == Role.ADMIN) {
            if (cvApprovals != null) {
                nav("CV review", () -> showBody("CV review", new CvApprovalPanel(cvApprovals, session), false));
            }
            if (workspace != null) {
                nav("Accounts", () -> showBody("Accounts", new WorkspacePanel(workspace, session, WorkspacePanel.Page.ACCOUNTS), false));
                nav("Data management", () -> showBody("Data management", new WorkspacePanel(workspace, session, WorkspacePanel.Page.DATA), false));
                nav("Activity log", () -> showBody("Activity log", new WorkspacePanel(workspace, session, WorkspacePanel.Page.ACTIVITY), false));
                nav("Application monitoring", () -> showBody("Application monitoring",
                        new WorkspacePanel(workspace, session, WorkspacePanel.Page.MONITOR), false));
            }
        }
        if (workspace != null) {
            nav("Notifications", () -> showNotices(role));
            if (role != Role.STUDENT) {
                nav("Reports", () -> showBody("Reports", new ReportsPanel(workspace, session), false));
            }
        }
        nav("My profile", () -> run(() -> identity.profile(session), this::editProfile));

        var footer = new JPanel(new BorderLayout(0, 8));
        footer.setOpaque(false);
        account = new JLabel();
        account.setForeground(Color.WHITE);
        footer.add(account, BorderLayout.NORTH);
        var signOut = WorkspaceStyle.button("Sign out", () -> {
            identity.logout(session);
            session = null;
            login();
        });
        footer.add(signOut, BorderLayout.SOUTH);
        sidebar.add(footer, BorderLayout.SOUTH);
        sidebar.setVisible(true);
    }

    private void nav(String label, Runnable action) {
        var button = WorkspaceStyle.button(label, action);
        button.setHorizontalAlignment(SwingConstants.LEADING);
        button.setForeground(Color.WHITE);
        button.setBackground(WorkspaceStyle.NAVY);
        button.setBorder(BorderFactory.createEmptyBorder(3, 9, 3, 9));
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        button.setPreferredSize(new Dimension(180, 36));
        button.setToolTipText(label);
        navigationLinks.add(button);
        navigationLinks.add(Box.createVerticalStrut(4));
        navigation.put(label, button);
    }

    private void select(String section) {
        navigation.forEach((name, button) -> {
            boolean current = name.equals(section);
            button.setBackground(current ? WorkspaceStyle.TEAL : WorkspaceStyle.NAVY);
            button.setFont(button.getFont().deriveFont(current ? Font.BOLD : Font.PLAIN));
            button.getAccessibleContext().setAccessibleDescription(current ? "Current section" : null);
        });
    }

    private void editProfile(Profile profile) {
        JPanel form = WorkspaceStyle.form();
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
        form.add(formActions(save, back));
        var security = WorkspaceStyle.actions();
        security.add(WorkspaceStyle.button("Change password", () -> credentialForm(profile)));
        security.add(WorkspaceStyle.button("Recovery codes", () -> issueRecoveryCodes(profile)));
        form.add(security);
        save.addActionListener(event -> {
            String newName = name.getText();
            String newPhone = phone.getText();
            String newAffiliation = affiliation.getText();
            String newAbout = about.getText();
            run(() -> identity.saveProfile(session, newName, newPhone, newAffiliation, newAbout), saved -> {
                updateAccount(saved);
                editProfile(saved);
                status.setText("Profile saved.");
            });
        });
        back.addActionListener(event -> run(() -> identity.profile(session), this::home));
        showBody("My profile", form);
        status.setText("Signed in as " + profile.email());
        getRootPane().setDefaultButton(save);
    }

    private void credentialForm(Profile profile) {
        boolean recovery = profile == null;
        var form = WorkspaceStyle.form();
        var email = new JTextField(28);
        var credential = new JPasswordField(28);
        var password = new JPasswordField(28);
        var confirmation = new JPasswordField(28);
        if (recovery) { field(form, "Email", email); }
        field(form, recovery ? "Saved recovery code" : "Current password", credential);
        field(form, "New password (10–128 characters)", password);
        field(form, "Confirm new password", confirmation);
        var save = WorkspaceStyle.button(recovery ? "Reset password" : "Change password", () -> {
            var address = email.getText();
            var sessionId = session;
            char[] existing = credential.getPassword();
            char[] replacement = password.getPassword();
            char[] repeated = confirmation.getPassword();
            credential.setText(""); password.setText(""); confirmation.setText("");
            run(() -> {
                try {
                    if (recovery) { identity.recoverPassword(address, existing, replacement, repeated); }
                    else { identity.changePassword(sessionId, existing, replacement, repeated); }
                    return true;
                } finally {
                    Arrays.fill(existing, '\0'); Arrays.fill(replacement, '\0'); Arrays.fill(repeated, '\0');
                }
            }, updated -> {
                session = null;
                login();
                status.setText("Password updated. Sign in with your new password.");
            });
        });
        var back = WorkspaceStyle.button(recovery ? "Back to sign in" : "Back to profile",
                () -> { if (recovery) { login(); } else { editProfile(profile); } });
        form.add(formActions(save, back));
        showBody(recovery ? "Account recovery" : "Change password", form);
        getRootPane().setDefaultButton(save);
        status.setText(recovery ? "Use a code you saved earlier. A successful reset uses up the whole set."
                : "Changing your password signs you out and revokes any saved recovery codes.");
    }

    private void issueRecoveryCodes(Profile profile) {
        var form = WorkspaceStyle.form();
        var password = new JPasswordField(28);
        field(form, "Current password", password);
        var generate = WorkspaceStyle.button("Generate new codes", () -> {
            var sessionId = session;
            char[] secret = password.getPassword();
            password.setText("");
            run(() -> {
                try { return identity.issueRecoveryCodes(sessionId, secret); }
                finally { Arrays.fill(secret, '\0'); }
            }, codes -> javax.swing.JOptionPane.showMessageDialog(this,
                    WorkspaceStyle.text("Save these codes somewhere private. They are shown once.\n"
                            + "A successful reset uses up the whole set.\n\n" + String.join("\n", codes)),
                    "Recovery codes", javax.swing.JOptionPane.PLAIN_MESSAGE));
        });
        var back = WorkspaceStyle.button("Back to profile", () -> editProfile(profile));
        form.add(formActions(generate, back));
        showBody("Recovery codes", form);
        getRootPane().setDefaultButton(generate);
        status.setText("Generating codes replaces any previous set.");
    }

    private void showMatches() {
        showBody("Job matches", new MatchesPanel(matches, cvs, session,
                id -> showBody("Job details",
                        new JobsPanel(jobs, session, Role.STUDENT, this::showMatches, id, applications), false),
                id -> jobs.saveBookmark(session, id),
                () -> showBody("My CV", new CvPanel(cvs, session, this::showMatches), false)), false);
    }

    private void showSavedJobs() {
        showBody("Saved jobs", new SavedJobsPanel(jobs, session,
                id -> showBody("Saved jobs", new JobsPanel(jobs, session, Role.STUDENT, this::showSavedJobs, id, applications,
                        "Back to saved jobs"), false),
                applications == null ? null : id -> showBody("My applications",
                        new ApplicationsPanel(applications, session, Role.STUDENT, id), false)), false);
    }

    private void showSavedSearches() {
        showBody("Saved searches", new SavedSearchPanel(jobs, session,
                search -> showBody("Saved searches", new JobsPanel(jobs, session, Role.STUDENT, this::showSavedSearches,
                        0, applications, "Back to saved searches", search), false)), false);
    }

    private void showNotices(Role role) {
        showBody("Notifications", new WorkspacePanel(workspace, session, WorkspacePanel.Page.NOTICES,
                role != Role.STUDENT || jobs == null ? null : id -> showBody("Notifications",
                        new JobsPanel(jobs, session, role, () -> showNotices(role), id, applications, "Back to notifications"), false)), false);
    }

    private void showBody(String title, Component body) {
        showBody(title, body, true);
    }

    private void showBody(String title, Component body, boolean scrollable) {
        content.removeAll();
        getRootPane().setDefaultButton(null);
        select(title.equals("Job details") ? "Job matches" : title);
        JLabel heading = new JLabel(title);
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 26f));
        content.add(heading, BorderLayout.NORTH);
        if (scrollable) {
            content.add(WorkspaceStyle.scroll(body), BorderLayout.CENTER);
        } else {
            content.add(body, BorderLayout.CENTER);
        }
        status.setForeground(WorkspaceStyle.MUTED);
        status.setText("");
        content.add(status, BorderLayout.SOUTH);
        content.revalidate();
        content.repaint();
    }

    private static JPanel centered(JPanel form) {
        var frame = new JPanel(new GridBagLayout());
        frame.add(form);
        return frame;
    }

    private static JPanel formActions(JButton primary, JButton secondary) {
        var actions = WorkspaceStyle.actions();
        actions.setOpaque(false);
        actions.setAlignmentX(Component.LEFT_ALIGNMENT);
        WorkspaceStyle.primary(primary);
        actions.add(primary);
        actions.add(secondary);
        return actions;
    }

    private static void field(JPanel panel, String label, javax.swing.JComponent input) {
        WorkspaceStyle.field(panel, label, input);
    }

    private <T> void run(Callable<T> task, Consumer<T> success) {
        run(task, success, () -> { });
    }

    private <T> void run(Callable<T> task, Consumer<T> success, Runnable failure) {
        if (busy) {
            return;
        }
        busy = true;
        Runnable restoreContent = WorkspaceStyle.disable(content);
        Runnable restoreSidebar = WorkspaceStyle.disable(sidebar);
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
                restoreContent.run();
                restoreSidebar.run();
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

    private record Services(IdentityService identity, smarthire.jobs.JobService jobs, smarthire.cv.CvService cvs,
            smarthire.applications.ApplicationService applications, smarthire.workspace.WorkspaceService workspace, smarthire.cv.CvApprovalService cvApprovals) { }

    private record LoginResult(UUID token, Profile profile) { }
}
