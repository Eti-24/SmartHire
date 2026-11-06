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
    private final JTextArea message = WorkspaceStyle.message("Loading CV review queue…");
    private boolean busy;
    private CvApproval.Status filter = CvApproval.Status.PENDING;

    CvApprovalPanel(CvApprovalService service, UUID session) {
        super(new BorderLayout(0, 10));
        this.service = service;
        this.session = session;
        add(body, BorderLayout.CENTER);
        add(message, BorderLayout.SOUTH);
        browse();
    }

    private void browse() {
        var controls = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 0));
        var status = new JComboBox<String>(new String[]{"All statuses", "Pending approval", "Approved", "Rejected"});
        status.setSelectedIndex(filter == null ? 0 : filter.ordinal() + 1);
        var label = new JLabel("CV approval");
        label.setLabelFor(status);
        controls.add(label);
        controls.add(status);
        button(controls, "Filter / refresh", () -> {
            int selected = status.getSelectedIndex();
            filter = selected == 0 ? null : CvApproval.Status.values()[selected - 1];
            browse();
        });

        var model = new DefaultTableModel(new String[]{"Student", "Confirmed CV", "Approval"}, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        var table = new JTable(model);
        table.setAutoCreateRowSorter(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        WorkspaceStyle.statusColumn(table, 2);
        table.getAccessibleContext().setAccessibleName("Confirmed CV versions");
        var preview = new WorkspaceStyle.Preview();
        preview.showRecord("CV review", "Select a student to preview their current confirmed CV.");
        var actions = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 0));
        var open = new JButton("Review CV version");
        WorkspaceStyle.primary(open);
        open.setEnabled(false);
        actions.add(open);
        body.removeAll();
        body.add(controls, BorderLayout.NORTH);
        body.add(WorkspaceStyle.split(WorkspaceStyle.table(table), preview), BorderLayout.CENTER);
        body.add(actions, BorderLayout.SOUTH);
        refresh();

        var requestedFilter = filter;
        run(() -> service.list(session, requestedFilter), submissions -> {
            for (var s : submissions) { model.addRow(new Object[]{s.name(), s.originalName(), approvalLabel(s.approval())}); }
            table.getSelectionModel().addListSelectionListener(event -> {
                if (event.getValueIsAdjusting()) { return; }
                int row = table.getSelectedRow();
                open.setEnabled(!busy && row >= 0);
                if (row >= 0) {
                    var submission = submissions.get(table.convertRowIndexToModel(row));
                    preview.showRecord(approvalLabel(submission.approval()), submission.name() + "\n" + submission.email()
                            + "\n\nConfirmed CV\n" + submission.originalName()
                            + "\n\n" + (submission.active() ? "Active student" : "Disabled student")
                            + "\n\nOpen this version to inspect the original document and confirmed qualifications.");
                } else {
                    preview.showRecord("CV review", "Select a student to preview their current confirmed CV.");
                }
            });
            open.addActionListener(event -> {
                int row = table.convertRowIndexToModel(table.getSelectedRow());
                run(() -> service.open(session, submissions.get(row).version()), this::details);
            });
            if (submissions.isEmpty()) {
                preview.showRecord("No CVs found", "Current confirmed CVs that match this filter will appear here.");
                message.setText("");
            } else {
                table.setRowSelectionInterval(0, 0);
                message.setText("Decisions apply to the exact confirmed CV version. Replacements and qualification edits require a new decision.");
            }
        });
    }

    private static String approvalLabel(CvApproval approval) {
        return approval.status() == CvApproval.Status.PENDING ? "Pending approval" : WorkspaceStyle.label(approval.status());
    }

    private void details(CvApprovalRepository.Review review) {
        var cv = review.cv(); var s = review.submission(); var q = cv.qualifications();
        var tabs = new JTabbedPane();
        var summary = new StringBuilder("Student: ").append(s.name()).append("\nEmail: ").append(s.email()).append("\nFile: ").append(s.originalName())
                .append("\nVersion: ").append(cv.id()).append("\nConfirmed: ").append(cv.reviewedAt()).append("\nCurrent: ").append(review.current())
                .append("\nActive student: ").append(s.active()).append("\n\nAdmin approval: ").append(approvalLabel(s.approval())).append("\nReason: ").append(s.approval().reason())
                .append("\nDecision time: ").append(s.approval().decidedAt() == null ? "Not decided" : s.approval().decidedAt())
                .append("\nReviewer account ID: ").append(s.approval().reviewerId() == null ? "Not decided" : s.approval().reviewerId());

        var qualifications = new StringBuilder("Skills: ").append(String.join(", ", q.skills())).append("\nDegree: ").append(q.degree() == null ? "Not provided" : q.degree()).append("\nField: ").append(q.field()).append("\n\nProjects\n");
        q.projects().forEach(p -> qualifications.append(p.evidence()).append("\nSkills: ").append(String.join(", ", p.skills())).append("\n\n"));
        qualifications.append("Work experience\n"); q.experience().forEach(e -> qualifications.append(e.evidence()).append("\n").append(e.start()).append(" to ").append(e.end()).append("\nTags: ").append(String.join(", ", e.skills())).append("\n\n"));
        qualifications.append("Certifications\n").append(String.join("\n", q.certifications()));
        tabs.addTab("Confirmed qualifications", text(qualifications.toString()));
        tabs.addTab("CV text", text(cv.extractedText()));
        tabs.addTab("Version and review details", text(summary.toString()));
        var actions = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 0));
        button(actions, "Save original CV…", () -> {
            var chooser = new JFileChooser(); chooser.setSelectedFile(new java.io.File(cv.originalName()));
            if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) { return; } var destination = chooser.getSelectedFile().toPath();
            run(() -> Files.exists(destination), exists -> {
                if (exists && JOptionPane.showConfirmDialog(this, "Replace the existing file?", "Save original CV", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) { return; }
                run(() -> { Files.write(destination, service.download(session, cv.id())); return true; }, ignored -> message.setText("Original CV saved. Open the saved file in your PDF/Word viewer."));
            });
        });
        if (review.current() && s.active() && s.approval().status() == CvApproval.Status.PENDING) {
            var approve = WorkspaceStyle.button("Approve this version", () -> decide(cv.id(), CvApproval.Status.APPROVED));
            WorkspaceStyle.primary(approve);
            actions.add(approve);
            button(actions, "Reject with reason", () -> decide(cv.id(), CvApproval.Status.REJECTED));
        }
        button(actions, "Back to CV list", this::browse);
        body.removeAll();
        var title = new JLabel(approvalLabel(s.approval()) + " · " + s.name() + " · " + s.originalName());
        title.setToolTipText(title.getText());
        body.add(title, BorderLayout.NORTH);
        body.add(tabs, BorderLayout.CENTER);
        body.add(actions, BorderLayout.SOUTH);
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
                try { T result = get(); message.setText(""); success.accept(result); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); message.setText("Interrupted. Try again."); }
                catch (java.util.concurrent.ExecutionException e) { message.setText(e.getCause() instanceof UserError ? e.getCause().getMessage() : "Could not complete CV review. Please try again."); }
            }
        }.execute();
    }
}
