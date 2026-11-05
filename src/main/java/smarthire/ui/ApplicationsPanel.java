package smarthire.ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.nio.file.Files;
import java.util.List;
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
    private final JLabel message = new JLabel("Loading…");
    private boolean busy, departed;

    ApplicationsPanel(ApplicationService service, UUID session, Role role, Runnable back) {
        super(new BorderLayout(8, 8));
        this.service = service; this.session = session; this.role = role;
        JButton home = new JButton("Back to overview");
        home.addActionListener(e -> { departed = true; back.run(); });
        add(home, BorderLayout.NORTH); add(body, BorderLayout.CENTER); add(message, BorderLayout.SOUTH);
        browse(null, null);
    }

    private void browse(Long jobId, Application.Status status) {
        run(() -> service.list(session, jobId, status), entries -> {
            var controls = new JPanel(new FlowLayout(FlowLayout.LEADING));
            var job = new JTextField(jobId == null ? "" : jobId.toString(), 8);
            var state = new JComboBox<String>(); state.addItem("All statuses");
            for (var item : Application.Status.values()) { state.addItem(item.name()); }
            if (status != null) { state.setSelectedItem(status.name()); }
            controls.add(new JLabel("Job ID (optional)")); controls.add(job); controls.add(state);
            button(controls, "Filter / refresh", () -> {
                try { browse(job.getText().isBlank() ? null : Long.parseLong(job.getText().strip()), state.getSelectedIndex() == 0 ? null : Application.Status.valueOf((String) state.getSelectedItem())); }
                catch (NumberFormatException e) { message.setText("Enter a numeric job ID or leave it blank."); }
            });
            var model = new DefaultTableModel(new String[]{"Job ID", "Job", "Student", "Status", "Current-policy rank %", "Original %"}, 0) {
                @Override public boolean isCellEditable(int row, int col) { return false; }
            };
            for (var entry : entries) {
                var a = entry.application();
                model.addRow(new Object[]{a.jobId(), entry.job().details().title(), a.submittedProfile().name(), a.status(),
                        String.format(java.util.Locale.ROOT, "%.2f", entry.ranking().percentage()), String.format(java.util.Locale.ROOT, "%.2f", a.originalScore())});
            }
            var table = new JTable(model); table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            var actions = new JPanel(new FlowLayout(FlowLayout.LEADING));
            button(actions, "Open selected application", () -> {
                if (table.getSelectedRow() < 0) { message.setText("Select an application first."); return; }
                UUID id = entries.get(table.getSelectedRow()).application().id();
                run(() -> service.open(session, id), this::details);
            });
            body.removeAll(); body.add(controls, BorderLayout.NORTH); body.add(new JScrollPane(table), BorderLayout.CENTER); body.add(actions, BorderLayout.SOUTH);
            message.setText(entries.isEmpty() ? "No applications match these filters." : "Ranking compares submitted evidence within each job using one current policy. Original scores stay unchanged.");
            refresh();
        });
    }

    private void details(ApplicationService.Entry entry) {
        var a = entry.application(); var p = a.submittedProfile();
        var tabs = new JTabbedPane();
        tabs.addTab("Application", text(entry.job().details().title() + " — " + entry.job().companyName()
                + "\nJob ID: " + a.jobId() + "\nStatus: " + a.status() + "\nSubmitted: " + a.submittedAt()
                + "\n\nSubmitted contact details\n" + p.name() + "\n" + p.email() + "\n" + p.phone() + "\n" + p.affiliation() + "\n" + p.about()
                + "\n\nCurrent match: " + String.format(java.util.Locale.ROOT, "%.2f%%", entry.ranking().percentage())
                + "\n" + (entry.ranking().eligible() ? "All mandatory requirements met." : "Mandatory requirements missing.")
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
        button(actions, "Save submitted CV", () -> {
            var chooser = new JFileChooser(); chooser.setSelectedFile(new java.io.File(entry.cv().originalName()));
            if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) { return; }
            var destination = chooser.getSelectedFile().toPath();
            if (Files.exists(destination) && JOptionPane.showConfirmDialog(this, "Replace the existing file?", "Save CV", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) { return; }
            run(() -> { byte[] bytes = service.download(session, a.id()); Files.write(destination, bytes); return true; }, ignored -> message.setText("Submitted CV saved."));
        });
        if (a.status().undecided()) {
            if (role == Role.STUDENT) { button(actions, "Withdraw", () -> change(a.id(), Application.Status.WITHDRAWN)); }
            else {
                if (a.status() == Application.Status.APPLIED) { button(actions, "Shortlist", () -> change(a.id(), Application.Status.SHORTLISTED)); }
                if (a.status() == Application.Status.SHORTLISTED) { button(actions, "Hire", () -> change(a.id(), Application.Status.HIRED)); }
                button(actions, "Reject", () -> change(a.id(), Application.Status.REJECTED));
            }
        }
        button(actions, "Back to applications", () -> browse(null, null));
        body.removeAll(); body.add(tabs, BorderLayout.CENTER); body.add(actions, BorderLayout.SOUTH); refresh();
        message.setText("Submitted CV and qualifications are preserved even after later profile changes.");
    }

    private void change(UUID id, Application.Status status) {
        run(() -> { service.transition(session, id, status); return service.open(session, id); }, this::details);
    }
    private static JScrollPane text(String value) { var area = new JTextArea(value, 16, 50); area.setEditable(false); area.setLineWrap(true); area.setWrapStyleWord(true); area.setCaretPosition(0); return new JScrollPane(area); }
    private static void button(JPanel panel, String label, Runnable action) { var b = new JButton(label); b.addActionListener(e -> action.run()); panel.add(b); }
    private void refresh() { revalidate(); repaint(); }
    private <T> void run(Callable<T> task, Consumer<T> success) {
        if (busy) { return; } busy = true; enabled(body, false); message.setText("Working…");
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return task.call(); }
            @Override protected void done() {
                busy = false; if (departed) { return; } enabled(body, true);
                try { T result = get(); success.accept(result); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); message.setText("Interrupted. Try again."); }
                catch (java.util.concurrent.ExecutionException e) { message.setText(e.getCause() instanceof UserError ? e.getCause().getMessage() : "Could not complete the operation. Check database and file access."); }
            }
        }.execute();
    }
    private static void enabled(java.awt.Component c, boolean value) { c.setEnabled(value); if (c instanceof java.awt.Container container) { for (var child : container.getComponents()) { enabled(child, value); } } }
}
