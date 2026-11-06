package smarthire.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import smarthire.cv.CvApproval;
import smarthire.cv.CvProfile;
import smarthire.cv.CvService;
import smarthire.identity.UserError;
import smarthire.jobs.Job.Degree;
import smarthire.skills.Skill;
import static smarthire.cv.CvProfile.*;

/** File work and services run off the EDT; values are captured before starting a worker. */
public final class CvPanel extends JPanel {
    private final CvService service;
    private final UUID session;
    private final Runnable onBack;
    private final JPanel body = new JPanel(new BorderLayout(12, 12));
    private final JTextArea message = WorkspaceStyle.message("Loading CV versions…");
    private CvService.Draft draft;
    private List<Skill> catalog = List.of();
    private boolean busy;
    private boolean departed;

    public CvPanel(CvService service, UUID session, Runnable onBack) {
        super(new BorderLayout(12, 12));
        this.service = service; this.session = session; this.onBack = onBack;
        JButton back = new JButton("Back to overview");
        back.addActionListener(event -> {
            if (draft != null) {
                work(() -> { draft.close(); return true; }, ignored -> { draft = null; leave(); });
            } else { leave(); }
        });
        if (onBack != null) { add(back, BorderLayout.NORTH); }
        add(body, BorderLayout.CENTER);
        add(message, BorderLayout.SOUTH);
        load();
    }

    private void leave() { departed = true; onBack.run(); }

    @Override public void removeNotify() {
        departed = true;
        if (!busy) { discardDraft(null); }
        super.removeNotify();
    }

    private void discardDraft(Object result) {
        CvService.Draft abandoned = result instanceof CvService.Draft returned ? returned : draft;
        draft = null;
        if (abandoned != null) {
            new SwingWorker<Void, Void>() {
                @Override protected Void doInBackground() { abandoned.close(); return null; }
            }.execute();
        }
    }

    private void load() {
        work(() -> new Overview(service.current(session).orElse(null), service.history(session), service.skills(session),
                service.approvals(session)), overview -> {
            catalog = overview.skills();
            var current = overview.current();
            var model = new DefaultTableModel(new Object[]{"CV version", "Current", "CV approval"}, 0) {
                @Override public boolean isCellEditable(int row, int column) { return false; }
            };
            for (var version : overview.history()) {
                model.addRow(new Object[]{version.originalName(), isCurrent(current, version) ? "Current" : "",
                        approvalLabel(overview.approvals().getOrDefault(version.id(), CvApproval.pending()))});
            }
            var history = new JTable(model);
            history.setAutoCreateRowSorter(true);
            history.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            history.getAccessibleContext().setAccessibleName("Confirmed CV versions");
            history.getColumnModel().getColumn(0).setPreferredWidth(220);
            history.getColumnModel().getColumn(1).setPreferredWidth(70);
            WorkspaceStyle.statusColumn(history, 2);
            var preview = new WorkspaceStyle.Preview();
            preview.showRecord("No confirmed CV", "Upload a CV, review the suggested qualifications and confirm the version.");
            var toolbar = WorkspaceStyle.actions();
            var upload = WorkspaceStyle.button(current == null ? "Upload CV" : "Replace CV", this::upload);
            WorkspaceStyle.primary(upload);
            toolbar.add(upload);
            button(toolbar, "Download CV template (.docx)",
                    () -> FileSaveDialog.template(this, FileSaveDialog.CV, message::setText));
            if (current != null) {
                button(toolbar, "Edit current review", () -> work(() -> service.edit(session), this::review));
            }
            button(toolbar, "Refresh approval status", this::load);

            var open = WorkspaceStyle.button("View selected review", () -> {
                var version = WorkspaceStyle.selected(history, overview.history());
                work(() -> service.review(session, version.id()), this::view);
            });
            var original = WorkspaceStyle.button("Save selected original…", () -> download(WorkspaceStyle.selected(history, overview.history())));
            open.setEnabled(false);
            original.setEnabled(false);
            WorkspaceStyle.previewSelection(history, overview.history(), version -> {
                open.setEnabled(version != null);
                original.setEnabled(version != null);
                if (version == null) {
                    preview.showRecord("CV versions", "Select a version to inspect its approval status.");
                    return;
                }
                boolean active = isCurrent(current, version);
                var approval = overview.approvals().getOrDefault(version.id(), CvApproval.pending());
                String review = version.originalName() + "\nConfirmed " + WorkspaceStyle.date(version.reviewedAt());
                if (active) {
                    var qualifications = current.qualifications();
                    review += "\n\nCurrent version\n" + qualifications.skills().size() + " skills · "
                            + qualifications.projects().size() + " projects · " + qualifications.experience().size() + " work entries";
                }
                preview.showRecord(approvalLabel(approval), review + "\n\n" + (active ? approval.message()
                        : "This historical version retains its approval decision." + (approval.reason().isBlank() ? "" : "\n" + approval.reason())));
            });
            var actions = WorkspaceStyle.actions();
            actions.add(open);
            actions.add(original);
            body.removeAll();
            body.add(toolbar, BorderLayout.NORTH);
            body.add(WorkspaceStyle.split(WorkspaceStyle.table(history), preview), BorderLayout.CENTER);
            body.add(actions, BorderLayout.SOUTH);
            message.setText("English text-based PDF or DOCX, up to 5 MB. Confirming sends the version for admin approval.");
            refresh();
        });
    }

    private static boolean isCurrent(CvProfile current, Version version) {
        return current != null && current.id().equals(version.id());
    }

    private static String approvalLabel(CvApproval approval) {
        return approval.status() == CvApproval.Status.PENDING ? "Pending approval" : WorkspaceStyle.label(approval.status());
    }

    private void upload() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose an English text-based CV (up to 5 MB)");
        chooser.setFileFilter(new FileNameExtensionFilter("PDF and DOCX documents", "pdf", "docx"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) { return; }
        Path source = chooser.getSelectedFile().toPath();
        work(() -> service.upload(session, source), this::review);
    }

    private void review(CvService.Draft pending) {
        draft = pending;
        Qualifications q = pending.suggestions();
        var qualifications = WorkspaceStyle.form();
        var projectsForm = new JPanel(new BorderLayout(0, 8));
        var experienceForm = new JPanel(new BorderLayout(0, 8));
        var certificatesForm = WorkspaceStyle.form();
        JList<Skill> technical = skillSelection(q.skills(), Skill.Type.TECHNICAL);
        JList<Skill> soft = skillSelection(q.skills(), Skill.Type.SOFT);
        field(qualifications, "Technical skills (Ctrl+click or Shift+arrow to select multiple)", new JScrollPane(technical));
        field(qualifications, "Soft skills", new JScrollPane(soft));
        JComboBox<String> degree = new JComboBox<>(); degree.addItem("Not provided");
        for (Degree value : Degree.values()) { degree.addItem(value.name()); }
        degree.setSelectedItem(q.degree() == null ? "Not provided" : q.degree().name());
        field(qualifications, "Highest degree", degree);
        JTextField subject = new JTextField(q.field()); field(qualifications, "Degree field (optional)", subject);

        DefaultTableModel projects = new DefaultTableModel(new Object[]{"Project evidence", "Skills (comma separated)"}, 0);
        for (Project p : q.projects()) { projects.addRow(new Object[]{p.evidence(), labels(p.skills())}); }
        JTable projectTable = table(projectsForm, "Include the skills supported by each project.", projects,
                new Object[]{"", ""});

        DefaultTableModel experience = new DefaultTableModel(new Object[]{"Work evidence", "Start YYYY-MM", "End YYYY-MM", "Ongoing", "Skill tags (comma separated)"}, 0) {
            @Override public Class<?> getColumnClass(int column) { return column == 3 ? Boolean.class : String.class; }
        };
        for (Experience e : q.experience()) { experience.addRow(new Object[]{e.evidence(), e.start().toString(), e.end().toString(), e.ongoing(), labels(e.skills())}); }
        JTable workTable = table(experienceForm, "Use YYYY-MM dates. Ongoing work ends at the month you confirm this review.", experience,
                new Object[]{"", "", "", false, ""});
        JTextArea certificates = new JTextArea(String.join("\n", q.certifications()), 4, 40);
        field(certificatesForm, "Certifications (one per line)", new JScrollPane(certificates));
        var actions = WorkspaceStyle.actions();
        var confirm = WorkspaceStyle.button("Confirm and submit for approval", () -> {
            try {
                if (!stop(projectTable) || !stop(workTable)) { message.setText("Finish editing the selected table cell."); return; }
                var projectItems = new ArrayList<Project>();
                for (int i = 0; i < projects.getRowCount(); i++) { projectItems.add(new Project(value(projects, i, 0), split(value(projects, i, 1)))); }
                var workItems = new ArrayList<Experience>();
                for (int i = 0; i < experience.getRowCount(); i++) {
                    String end = value(experience, i, 2);
                    workItems.add(new Experience(value(experience, i, 0), YearMonth.parse(value(experience, i, 1)),
                            end.isBlank() ? null : YearMonth.parse(end), Boolean.TRUE.equals(experience.getValueAt(i, 3)), split(value(experience, i, 4))));
                }
                var confirmedSkills = new ArrayList<String>();
                technical.getSelectedValuesList().forEach(skill -> confirmedSkills.add(skill.id()));
                soft.getSelectedValuesList().forEach(skill -> confirmedSkills.add(skill.id()));
                Qualifications input = new Qualifications(confirmedSkills, degree.getSelectedIndex() == 0 ? null : Degree.valueOf((String) degree.getSelectedItem()),
                        subject.getText(), projectItems, workItems, certificates.getText().lines().map(String::strip).filter(s -> !s.isEmpty()).toList());
                work(() -> service.confirm(session, pending, input), saved -> { draft = null; load(); });
            } catch (java.time.DateTimeException exception) { message.setText("Use valid YYYY-MM work dates, such as 2025-01."); }
        });
        WorkspaceStyle.primary(confirm);
        actions.add(confirm);
        button(actions, "Cancel review", () -> work(() -> { pending.close(); return true; }, ignored -> { draft = null; load(); }));
        var tabs = new JTabbedPane();
        tabs.addTab("Skills and education", WorkspaceStyle.scroll(qualifications));
        tabs.addTab("Projects", projectsForm);
        tabs.addTab("Work experience", experienceForm);
        tabs.addTab("Certifications", WorkspaceStyle.scroll(certificatesForm));
        tabs.addTab("Extracted text", WorkspaceStyle.text(pending.extractedText()));
        tabs.addTab("Skill catalog", WorkspaceStyle.text(catalog.stream().map(s -> s.name() + " [" + s.id() + "] · " + s.type())
                .collect(java.util.stream.Collectors.joining("\n"))));
        body.removeAll();
        body.add(WorkspaceStyle.message("Review " + pending.originalName()
                + "\nCheck the suggestions and confirm only qualifications you can support. Blank categories are allowed."), BorderLayout.NORTH);
        body.add(tabs, BorderLayout.CENTER);
        body.add(actions, BorderLayout.SOUTH);
        refresh();
        message.setText("Confirming sends a new version for admin approval. Leaving My CV or cancelling discards this draft.");
    }

    private void view(CvProfile profile) {
        work(() -> service.approval(session, profile.id()), approval -> view(profile, approval));
    }

    private void view(CvProfile profile, smarthire.cv.CvApproval approval) {
        Qualifications q = profile.qualifications();
        StringBuilder summary = new StringBuilder("File: " + profile.originalName() + "\nConfirmed: " + profile.reviewedAt() + "\nAdmin approval: " + approval.status() + "\n" + approval.message()
                + "\nSkills: " + labels(q.skills()) + "\nDegree: " + (q.degree() == null ? "Not provided" : q.degree()) + " " + q.field() + "\n\nProjects\n");
        for (Project p : q.projects()) { summary.append(p.evidence()).append("\nSkills: ").append(labels(p.skills())).append("\n\n"); }
        summary.append("Experience\n");
        for (Experience e : q.experience()) { summary.append(e.evidence()).append("\n").append(e.start()).append(" to ").append(e.end())
                .append(e.ongoing() ? " (ongoing, end frozen at review)" : "").append("\nTags: ").append(labels(e.skills())).append("\n\n"); }
        summary.append("Certifications\n").append(String.join("\n", q.certifications()));
        JTabbedPane tabs = new JTabbedPane(); tabs.addTab("Confirmed review", WorkspaceStyle.text(summary.toString()));
        tabs.addTab("Extracted text", WorkspaceStyle.text(profile.extractedText()));
        JPanel actions = WorkspaceStyle.actions(); button(actions, "Back to CV versions", this::load);
        button(actions, "Save original…", () -> download(new Version(profile.id(), profile.originalName(), profile.reviewedAt())));
        body.removeAll(); body.add(tabs); body.add(actions, BorderLayout.SOUTH); refresh();
        message.setText("This saved review is immutable. Edit the current review to create a new version.");
    }

    private void download(Version profile) {
        JFileChooser chooser = new JFileChooser(); chooser.setSelectedFile(new java.io.File(profile.originalName()));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) { return; }
        Path destination = chooser.getSelectedFile().toPath();
        // Confirm replacement explicitly; no file-system access runs on the EDT.
        work(() -> Files.exists(destination), exists -> {
            if (exists && JOptionPane.showConfirmDialog(this, "Replace the selected file?", "Save original CV", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) { return; }
            work(() -> { Files.write(destination, service.download(session, profile.id())); return true; }, ignored -> message.setText("Original CV saved."));
        });
    }

    private String labels(List<String> ids) { return ids.stream().map(id -> catalog.stream().filter(s -> s.id().equals(id)).map(Skill::name).findFirst().orElse(id)).collect(java.util.stream.Collectors.joining(", ")); }
    private JList<Skill> skillSelection(List<String> selected, Skill.Type type) {
        Skill[] values = catalog.stream().filter(skill -> skill.type() == type).toArray(Skill[]::new);
        JList<Skill> list = new JList<>(values);
        list.setVisibleRowCount(4);
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> source, Object value, int index, boolean chosen, boolean focus) {
                Skill skill = (Skill) value;
                return super.getListCellRendererComponent(source, skill.name(), index, chosen, focus);
            }
        });
        for (int i = 0; i < values.length; i++) { if (selected.contains(values[i].id())) { list.addSelectionInterval(i, i); } }
        return list;
    }
    private static List<String> split(String value) { return value.isBlank() ? List.of() : Arrays.stream(value.split(",", -1)).map(String::strip).toList(); }
    private static String value(DefaultTableModel model, int row, int column) { Object value = model.getValueAt(row, column); return value == null ? "" : value.toString().strip(); }
    private static boolean stop(JTable table) { return !table.isEditing() || table.getCellEditor().stopCellEditing(); }
    private static void field(JPanel form, String label, JComponent component) { WorkspaceStyle.field(form, label, component); }

    private static JTable table(JPanel form, String guidance, DefaultTableModel model, Object[] blank) {
        WorkspaceStyle.surface(form, 12);
        form.add(WorkspaceStyle.message(guidance), BorderLayout.NORTH);
        var table = new JTable(model);
        table.setSurrendersFocusOnKeystroke(true);
        form.add(WorkspaceStyle.table(table), BorderLayout.CENTER);
        var actions = WorkspaceStyle.actions();
        button(actions, "Add row", () -> { if (stop(table)) { model.addRow(blank); } });
        button(actions, "Remove selected row", () -> {
            if (stop(table) && table.getSelectedRow() >= 0) { model.removeRow(table.getSelectedRow()); }
        });
        form.add(actions, BorderLayout.SOUTH);
        return table;
    }

    private static void button(JPanel panel, String label, Runnable task) { panel.add(WorkspaceStyle.button(label, task)); }

    private void refresh() { revalidate(); repaint(); }

    private <T> void work(Callable<T> task, Consumer<T> success) {
        if (busy) { return; }
        busy = true;
        Runnable restore = WorkspaceStyle.disable(body);
        message.setText("Working…");
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return task.call(); }
            @Override protected void done() {
                busy = false;
                if (!departed) { restore.run(); }
                T result = null;
                try {
                    result = get();
                    if (!departed) {
                        message.setText("");
                        success.accept(result);
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    if (!departed) { message.setText("Interrupted. Try again."); }
                }
                catch (java.util.concurrent.ExecutionException exception) {
                    if (!departed) {
                        message.setText(exception.getCause() instanceof UserError ? exception.getCause().getMessage() : "The operation failed. Check file access and the database, then try again.");
                    }
                } finally {
                    if (departed) { discardDraft(result); }
                }
            }
        }.execute();
    }
    private record Overview(CvProfile current, List<Version> history, List<Skill> skills, java.util.Map<UUID, smarthire.cv.CvApproval> approvals) { }
}
