package smarthire.ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.nio.file.Files;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import smarthire.applications.Application;
import smarthire.applications.ApplicationService;
import smarthire.identity.Role;
import smarthire.identity.UserError;

final class ApplicationsPanel extends JPanel {
    private final ApplicationService service;
    private final UUID session;
    private final Role role;
    private final JPanel body = new JPanel(new BorderLayout(8, 8));
    private final JTextArea message = WorkspaceStyle.message("Loading applications…");
    private Long jobFilter;
    private Application.Status statusFilter;
    private boolean busy;

    ApplicationsPanel(ApplicationService service, UUID session, Role role) {
        this(service, session, role, null);
    }

    ApplicationsPanel(ApplicationService service, UUID session, Role role, UUID applicationId) {
        super(new BorderLayout(0, 10));
        this.service = service;
        this.session = session;
        this.role = role;
        add(body, BorderLayout.CENTER);
        add(message, BorderLayout.SOUTH);
        if (applicationId == null) { browse(); }
        else { run(() -> service.open(session, applicationId), this::details); }
    }

    private void browse() {
        var controls = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 0));
        var job = new JTextField(jobFilter == null ? "" : jobFilter.toString(), 7);
        var jobLabel = new JLabel("Job ID (optional)");
        jobLabel.setLabelFor(job);
        var state = new JComboBox<String>();
        state.addItem("All statuses");
        for (var item : Application.Status.values()) { state.addItem(WorkspaceStyle.label(item)); }
        if (statusFilter != null) { state.setSelectedIndex(statusFilter.ordinal() + 1); }
        var stateLabel = new JLabel("Application status");
        stateLabel.setLabelFor(state);
        controls.add(jobLabel);
        controls.add(job);
        controls.add(stateLabel);
        controls.add(state);
        var filter = WorkspaceStyle.button("Filter / refresh", () -> {
            try {
                jobFilter = job.getText().isBlank() ? null : Long.parseLong(job.getText().strip());
                statusFilter = state.getSelectedIndex() == 0 ? null : Application.Status.values()[state.getSelectedIndex() - 1];
                browse();
            } catch (NumberFormatException e) {
                message.setText("Enter a numeric job ID or leave it blank.");
            }
        });
        controls.add(filter);

        boolean recruiter = role == Role.COMPANY;
        String[] columns = recruiter ? new String[]{"Applicant", "Job", "Status", "Current match"}
                : new String[]{"Job", "Company", "Status"};
        var model = new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int col) { return false; }
        };
        var table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        WorkspaceStyle.statusColumn(table, 2);
        table.getAccessibleContext().setAccessibleName(recruiter ? "Applicants" : "My applications");
        var preview = new WorkspaceStyle.Preview();
        preview.showRecord("Applications", "Select a row to preview the submitted evidence.");
        var actions = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 0));
        var open = new JButton("Open application");
        WorkspaceStyle.primary(open);
        open.setEnabled(false);
        actions.add(open);
        body.removeAll();
        body.add(controls, BorderLayout.NORTH);
        body.add(WorkspaceStyle.split(WorkspaceStyle.table(table), preview), BorderLayout.CENTER);
        body.add(actions, BorderLayout.SOUTH);
        refresh();

        Long requestedJob = jobFilter;
        var requestedStatus = statusFilter;
        run(() -> service.list(session, requestedJob, requestedStatus), entries -> {
            for (var entry : entries) {
                var a = entry.application();
                model.addRow(recruiter
                        ? new Object[]{a.submittedProfile().name(), entry.job().details().title(),
                                WorkspaceStyle.label(a.status()), String.format(Locale.ROOT, "%.2f%%", entry.ranking().percentage())}
                        : new Object[]{entry.job().details().title(), entry.job().companyName(), WorkspaceStyle.label(a.status())});
            }
            table.getSelectionModel().addListSelectionListener(event -> {
                if (event.getValueIsAdjusting()) { return; }
                int selected = table.getSelectedRow();
                open.setEnabled(!busy && selected >= 0);
                if (selected >= 0) { showPreview(preview, entries.get(selected)); }
                else { preview.showRecord("Applications", "Select a row to preview the submitted evidence."); }
            });
            open.addActionListener(event -> {
                UUID id = entries.get(table.getSelectedRow()).application().id();
                run(() -> service.open(session, id), this::details);
            });
            open.setEnabled(false);
            if (entries.isEmpty()) {
                preview.showRecord("No applications found", "Applications that match these filters will appear here.");
                message.setText("");
            } else {
                table.setRowSelectionInterval(0, 0);
                message.setText("Current rankings compare submitted qualifications within each job using the same policy.");
            }
        });
    }

    private void showPreview(WorkspaceStyle.Preview preview, ApplicationService.Entry entry) {
        var a = entry.application();
        var rank = entry.ranking();
        String mandatory = rank.eligible() ? "All mandatory requirements met." : String.join("\n", rank.failedMandatory());
        preview.showRecord(WorkspaceStyle.label(a.status()), a.submittedProfile().name() + "\n"
                + entry.job().details().title() + "\n\nMandatory requirements\n" + mandatory
                + "\n\nCurrent-policy match\n" + String.format(Locale.ROOT, "%.2f%%", rank.percentage())
                + "\n\nOriginal submission score\n" + String.format(Locale.ROOT, "%.2f%%", a.originalScore()));
    }

    private void details(ApplicationService.Entry entry) {
        var a = entry.application(); var p = a.submittedProfile();
        var tabs = new JTabbedPane();
        tabs.addTab("Application", text(entry.job().details().title() + " — " + entry.job().companyName()
                + "\nJob ID: " + a.jobId() + "\nStatus: " + WorkspaceStyle.label(a.status()) + "\nSubmitted: " + a.submittedAt()
                + "\n\nSubmitted contact details\n" + p.name() + "\n" + p.email() + "\n" + p.phone() + "\n" + p.affiliation() + "\n" + p.about()
                + "\n\n" + (entry.ranking().eligible() ? "All mandatory requirements met." : "Mandatory requirements missing.")
                + "\nCurrent-policy match: " + String.format(Locale.ROOT, "%.2f%%", entry.ranking().percentage())
                + "\nCalculated from the qualifications submitted with this application. Original assessment and score details are in separate tabs."));
        tabs.addTab("Current score details", text(ApplicationService.explanation(entry.ranking())));
        tabs.addTab("Original assessment", text(a.originalExplanation()));
        tabs.addTab("Submitted CV text", text(entry.cv().extractedText()));
        var q = entry.cv().qualifications();
        var qualifications = new StringBuilder("Skills: ").append(String.join(", ", q.skills()))
                .append("\nDegree: ").append(q.degree() == null ? "Not specified" : q.degree())
                .append("\nField: ").append(q.field().isBlank() ? "Not specified" : q.field()).append("\n\nProjects:\n");
        q.projects().forEach(project -> qualifications.append("• ").append(project.evidence()).append("\n  Skills: ").append(String.join(", ", project.skills())).append('\n'));
        qualifications.append("\nWork experience:\n");
        q.experience().forEach(work -> qualifications.append("• ").append(work.evidence()).append("\n  ").append(work.start()).append(" to ").append(work.end())
                .append(work.ongoing() ? " (ongoing at review)" : "").append("\n  Skills: ").append(String.join(", ", work.skills())).append('\n'));
        qualifications.append("\nCertifications: ").append(String.join(", ", q.certifications()));
        tabs.addTab("Submitted qualifications", text(qualifications.toString()));
        var actions = new JPanel(new FlowLayout(FlowLayout.LEADING));
        tabs.addTab("Interview", new InterviewsPanel(service, session, role, a.id()));
        if (role == Role.COMPANY) { tabs.addTab("Feedback", new RecruiterFeedbackPanel(service, session, a.id())); }
        button(actions, "Save submitted CV", () -> {
            var chooser = new JFileChooser(); chooser.setSelectedFile(new java.io.File(entry.cv().originalName()));
            if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) { return; }
            var destination = chooser.getSelectedFile().toPath();
            run(() -> Files.exists(destination), exists -> {
                if (exists && JOptionPane.showConfirmDialog(this, "Replace the existing file?", "Save CV",
                        JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) { return; }
                run(() -> {
                    Files.write(destination, service.download(session, a.id()));
                    return true;
                }, ignored -> message.setText("Submitted CV saved."));
            });
        });
        if (a.status().undecided()) {
            if (role == Role.STUDENT) { button(actions, "Withdraw", () -> change(a.id(), Application.Status.WITHDRAWN)); }
            else {
                if (a.status() == Application.Status.APPLIED) { button(actions, "Shortlist", () -> change(a.id(), Application.Status.SHORTLISTED)); }
                if (a.status() == Application.Status.SHORTLISTED && entry.job().remainingVacancies() > 0) {
                    button(actions, "Hire", () -> change(a.id(), Application.Status.HIRED));
                }
                button(actions, "Reject", () -> change(a.id(), Application.Status.REJECTED));
            }
        }
        button(actions, "Back to applications", this::browse);
        body.removeAll(); body.add(tabs, BorderLayout.CENTER); body.add(actions, BorderLayout.SOUTH); refresh();
        message.setText("Submitted CV and qualifications are preserved even after later profile changes.");
    }

    private void change(UUID id, Application.Status status) {
        run(() -> { service.transition(session, id, status); return service.open(session, id); }, this::details);
    }
    private static JScrollPane text(String value) { return WorkspaceStyle.text(value); }
    private static void button(JPanel panel, String label, Runnable action) { var b = new JButton(label); b.addActionListener(e -> action.run()); panel.add(b); }
    private void refresh() { revalidate(); repaint(); }
    private <T> void run(Callable<T> task, Consumer<T> success) {
        if (busy) { return; }
        busy = true;
        Runnable restore = WorkspaceStyle.disable(body);
        message.setText("Working…");
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return task.call(); }
            @Override protected void done() {
                busy = false;
                if (!isDisplayable()) { return; }
                restore.run();
                try {
                    T result = get();
                    message.setText("");
                    success.accept(result);
                }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); message.setText("Interrupted. Try again."); }
                catch (java.util.concurrent.ExecutionException e) { message.setText(e.getCause() instanceof UserError ? e.getCause().getMessage() : "Could not complete the operation. Check database and file access."); }
            }
        }.execute();
    }
}
