package smarthire.ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import smarthire.cv.*;
import smarthire.identity.UserError;

/** Admin CV review; service calls and original-file exports run on workers. */
final class CvApprovalPanel extends JPanel {
    private final CvApprovalService service;
    private final UUID session;
    private final JPanel body = new JPanel(new BorderLayout(8, 8));
    private final JTextArea message = new JTextArea(3, 40);
    private boolean busy, departed;
    private CvApproval.Status filter = CvApproval.Status.PENDING;
    CvApprovalPanel(CvApprovalService service, UUID session, Runnable back) {
        super(new BorderLayout(8, 8)); this.service = service; this.session = session;
        var home = new JButton("Back to overview"); home.addActionListener(e -> { departed = true; back.run(); });
        message.setEditable(false); message.setLineWrap(true); message.setWrapStyleWord(true);
        add(home, BorderLayout.NORTH); add(body, BorderLayout.CENTER); add(message, BorderLayout.SOUTH); browse();
    }
    private void browse() {
        run(() -> service.list(session, filter), submissions -> {
            var controls = new JPanel(new FlowLayout(FlowLayout.LEADING));
            var status = new JComboBox<String>(new String[]{"All statuses", "PENDING", "APPROVED", "REJECTED"});
            status.setSelectedItem(filter == null ? "All statuses" : filter.name()); controls.add(new JLabel("Admin approval")); controls.add(status);
            button(controls, "Filter / refresh", () -> { filter = status.getSelectedIndex() == 0 ? null : CvApproval.Status.valueOf((String) status.getSelectedItem()); browse(); });
            var model = new DefaultTableModel(new String[]{"Student ID", "Student", "Email", "File", "Confirmed", "Status", "Active"}, 0) {
                @Override public boolean isCellEditable(int row, int column) { return false; }
            };
            for (var s : submissions) { model.addRow(new Object[]{s.studentId(), s.name(), s.email(), s.originalName(), s.confirmedAt(), s.approval().status(), s.active()}); }
            var table = new JTable(model); table.setAutoCreateRowSorter(true); table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            var actions = new JPanel(new FlowLayout(FlowLayout.LEADING));
            button(actions, "Review selected CV", () -> {
                if (table.getSelectedRow() < 0) { message.setText("Select a student's CV first."); return; }
                var id = submissions.get(table.convertRowIndexToModel(table.getSelectedRow())).version(); run(() -> service.open(session, id), this::details);
            });
            body.removeAll(); body.add(controls, BorderLayout.NORTH); body.add(new JScrollPane(table), BorderLayout.CENTER); body.add(actions, BorderLayout.SOUTH);
            message.setText(submissions.isEmpty() ? "No current CVs match this filter." : "Only confirmed current CV versions are listed. Open a CV to inspect its file and qualifications before deciding."); refresh();
        });
    }
    private void details(CvApprovalRepository.Review review) {
        var cv = review.cv(); var s = review.submission(); var q = cv.qualifications();
        var tabs = new JTabbedPane();
        var summary = new StringBuilder("Student: ").append(s.name()).append("\nEmail: ").append(s.email()).append("\nFile: ").append(s.originalName())
                .append("\nVersion: ").append(cv.id()).append("\nConfirmed: ").append(cv.reviewedAt()).append("\nCurrent: ").append(review.current())
                .append("\nActive student: ").append(s.active()).append("\n\nAdmin approval: ").append(s.approval().status()).append("\nReason: ").append(s.approval().reason())
                .append("\nDecision time: ").append(s.approval().decidedAt() == null ? "Not decided" : s.approval().decidedAt())
                .append("\nReviewer account ID: ").append(s.approval().reviewerId() == null ? "Not decided" : s.approval().reviewerId());
        tabs.addTab("Submission", text(summary.toString()));
        var qualifications = new StringBuilder("Skills: ").append(String.join(", ", q.skills())).append("\nDegree: ").append(q.degree() == null ? "Not provided" : q.degree()).append("\nField: ").append(q.field()).append("\n\nProjects\n");
        q.projects().forEach(p -> qualifications.append(p.evidence()).append("\nSkills: ").append(String.join(", ", p.skills())).append("\n\n"));
        qualifications.append("Work experience\n"); q.experience().forEach(e -> qualifications.append(e.evidence()).append("\n").append(e.start()).append(" to ").append(e.end()).append("\nTags: ").append(String.join(", ", e.skills())).append("\n\n"));
        qualifications.append("Certifications\n").append(String.join("\n", q.certifications()));
        tabs.addTab("Confirmed qualifications", text(qualifications.toString())); tabs.addTab("CV text", text(cv.extractedText()));
        var actions = new JPanel(new java.awt.GridLayout(0, 1, 0, 6));
        button(actions, "Save original CV…", () -> {
            var chooser = new JFileChooser(); chooser.setSelectedFile(new java.io.File(cv.originalName()));
            if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) { return; } var destination = chooser.getSelectedFile().toPath();
            run(() -> Files.exists(destination), exists -> {
                if (exists && JOptionPane.showConfirmDialog(this, "Replace the existing file?", "Save original CV", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) { return; }
                run(() -> { Files.write(destination, service.download(session, cv.id())); return true; }, ignored -> message.setText("Original CV saved. Open the saved file in your PDF/Word viewer."));
            });
        });
        if (review.current() && s.active() && s.approval().status() == CvApproval.Status.PENDING) {
            button(actions, "Approve this CV version", () -> decide(cv.id(), CvApproval.Status.APPROVED));
            button(actions, "Reject with reason", () -> decide(cv.id(), CvApproval.Status.REJECTED));
        }
        button(actions, "Back to CV list", this::browse);
        body.removeAll(); body.add(tabs, BorderLayout.CENTER); body.add(actions, BorderLayout.SOUTH);
        message.setText("Check the original CV, extracted text and confirmed qualifications. A decision applies only to this exact version. Replaced versions cannot be approved."); refresh();
    }
    private void decide(UUID version, CvApproval.Status status) {
        String reason = "";
        if (status == CvApproval.Status.REJECTED) {
            reason = JOptionPane.showInputDialog(this, "Explain what the student must correct (1–1000 characters):", "Reject CV", JOptionPane.QUESTION_MESSAGE);
            if (reason == null) { return; }
        } else if (JOptionPane.showConfirmDialog(this, "Approve this exact CV version for job applications?", "Approve CV", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) { return; }
        String captured = reason; run(() -> { service.decide(session, version, status, captured); return true; }, ignored -> browse());
    }
    private static JScrollPane text(String value) { var area = new JTextArea(value, 18, 55); area.setEditable(false); area.setLineWrap(true); area.setWrapStyleWord(true); area.setCaretPosition(0); return new JScrollPane(area); }
    private static void button(JPanel panel, String label, Runnable action) { var b = new JButton(label); b.addActionListener(e -> action.run()); panel.add(b); }
    private void refresh() { revalidate(); repaint(); }
    private <T> void run(Callable<T> task, Consumer<T> success) {
        if (busy || departed) { return; } busy = true; message.setText("Working…");
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return task.call(); }
            @Override protected void done() {
                busy = false; if (departed) { return; }
                try { T result = get(); message.setText("Ready"); success.accept(result); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); message.setText("Interrupted. Try again."); }
                catch (java.util.concurrent.ExecutionException e) { message.setText(e.getCause() instanceof UserError ? e.getCause().getMessage() : "Could not complete CV review. Please try again."); }
            }
        }.execute();
    }
}
