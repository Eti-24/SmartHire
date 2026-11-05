package smarthire.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
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
    private final JTextArea message = new JTextArea(3, 45);
    private CvService.Draft draft;
    private List<Skill> catalog = List.of();
    private boolean busy;
    private volatile boolean departed;

    public CvPanel(CvService service, UUID session, Runnable onBack) {
        super(new BorderLayout(12, 12));
        this.service = service; this.session = session; this.onBack = onBack;
        JButton back = new JButton("Back to overview");
        back.addActionListener(event -> {
            if (draft != null) {
                work(() -> { draft.close(); return true; }, ignored -> { draft = null; leave(); });
            } else { leave(); }
        });
        add(back, BorderLayout.NORTH); add(body, BorderLayout.CENTER);
        message.setEditable(false); message.setLineWrap(true); message.setWrapStyleWord(true);
        add(message, BorderLayout.SOUTH);
        load();
    }

    private void leave() { departed = true; onBack.run(); }

    private void load() {
        work(() -> new Overview(service.current(session).orElse(null), service.history(session), service.skills(session), service.approvals(session)), overview -> {
            catalog = overview.skills();
            body.removeAll();
            CvProfile current = overview.current();
            JTextArea description = text(current == null
                    ? "No reviewed CV yet. Upload a CV, correct the suggestions, and confirm your qualifications."
                    : "Current CV: " + current.originalName() + "\nReviewed: " + current.reviewedAt()
                            + "\n" + current.qualifications().skills().size() + " skills · " + current.qualifications().projects().size()
                            + " projects · " + current.qualifications().experience().size() + " work entries\n"
                            + overview.approvals().getOrDefault(current.id(), smarthire.cv.CvApproval.pending()).message(), 6);
            body.add(description, BorderLayout.NORTH);
            DefaultTableModel model = new DefaultTableModel(new Object[]{"Confirmed", "File", "Current", "Admin approval"}, 0) {
                @Override public boolean isCellEditable(int row, int column) { return false; }
            };
            for (Version version : overview.history()) {
                model.addRow(new Object[]{version.reviewedAt(), version.originalName(), current != null && current.id().equals(version.id()) ? "Yes" : "",
                        overview.approvals().getOrDefault(version.id(), smarthire.cv.CvApproval.pending()).status()});
            }
            JTable history = new JTable(model); history.setAutoCreateRowSorter(true); history.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            body.add(new JScrollPane(history), BorderLayout.CENTER);
            JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
            button(actions, "Upload / replace CV", this::upload);
            if (current != null) { button(actions, "Edit current review", () -> work(() -> service.edit(session), this::review)); }
            button(actions, "View selected review", () -> selected(history, overview.history(), version -> work(() -> service.review(session, version.id()), this::view)));
            button(actions, "Save selected original…", () -> selected(history, overview.history(), this::download));
            button(actions, "Refresh approval status", this::load);
            body.add(actions, BorderLayout.SOUTH);
            message.setText("English text-based PDF or DOCX, up to 5 MB. Confirming submits this version for admin approval. Previous versions are retained.");
            refresh();
        });
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
        JPanel form = form();
        form.add(text("Review " + pending.originalName() + "\nSuggestions can be incomplete or incorrect. Confirm only qualifications you can support. Blank categories are allowed.", 3));
        JList<Skill> technical = skillSelection(q.skills(), Skill.Type.TECHNICAL);
        JList<Skill> soft = skillSelection(q.skills(), Skill.Type.SOFT);
        field(form, "Technical skills (Ctrl+click to select or clear; Shift+arrows on keyboard)", new JScrollPane(technical));
        field(form, "Soft skills (select only those you confirm)", new JScrollPane(soft));
        JComboBox<String> degree = new JComboBox<>(); degree.addItem("Not provided");
        for (Degree value : Degree.values()) { degree.addItem(value.name()); }
        degree.setSelectedItem(q.degree() == null ? "Not provided" : q.degree().name());
        field(form, "Highest degree (demo order: secondary, diploma, bachelor, master, doctorate)", degree);
        JTextField subject = new JTextField(q.field()); field(form, "Degree field (optional)", subject);

        DefaultTableModel projects = new DefaultTableModel(new Object[]{"Project evidence", "Skills (comma separated)"}, 0);
        for (Project p : q.projects()) { projects.addRow(new Object[]{p.evidence(), labels(p.skills())}); }
        JTable projectTable = table(form, "Projects: only skills supported by each project count as project evidence.", projects,
                new Object[]{"", ""});

        DefaultTableModel experience = new DefaultTableModel(new Object[]{"Work evidence", "Start YYYY-MM", "End YYYY-MM", "Ongoing", "Skill tags (comma separated)"}, 0) {
            @Override public Class<?> getColumnClass(int column) { return column == 3 ? Boolean.class : String.class; }
        };
        for (Experience e : q.experience()) { experience.addRow(new Object[]{e.evidence(), e.start().toString(), e.end().toString(), e.ongoing(), labels(e.skills())}); }
        JTable workTable = table(form, "Experience: use YYYY-MM dates. Ongoing end freezes at the month you confirm this review.", experience,
                new Object[]{"", "", "", false, ""});
        JTextArea certificates = new JTextArea(String.join("\n", q.certifications()), 4, 40);
        field(form, "Certifications (one per line)", new JScrollPane(certificates));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        button(actions, "Confirm and submit for approval", () -> {
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
        button(actions, "Cancel review", () -> work(() -> { pending.close(); return true; }, ignored -> { draft = null; load(); }));
        form.add(actions);
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Review qualifications", new JScrollPane(form));
        tabs.addTab("Extracted text", new JScrollPane(text(pending.extractedText(), 16)));
        tabs.addTab("Skill catalog", new JScrollPane(text(catalog.stream().map(s -> s.name() + " [" + s.id() + "] · " + s.type()).collect(java.util.stream.Collectors.joining("\n")), 16)));
        body.removeAll(); body.add(tabs); refresh();
        message.setText("Confirming creates a new version awaiting admin approval, including qualification-only edits. Your previous CV stays current until confirmation. Cancel discards this draft.");
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
        JTabbedPane tabs = new JTabbedPane(); tabs.addTab("Confirmed review", new JScrollPane(text(summary.toString(), 16)));
        tabs.addTab("Extracted text", new JScrollPane(text(profile.extractedText(), 16)));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT)); button(actions, "Back to CV versions", this::load);
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
                return super.getListCellRendererComponent(source, skill.name() + " [" + skill.id() + "]", index, chosen, focus);
            }
        });
        for (int i = 0; i < values.length; i++) { if (selected.contains(values[i].id())) { list.addSelectionInterval(i, i); } }
        return list;
    }
    private static List<String> split(String value) { return value.isBlank() ? List.of() : Arrays.stream(value.split(",", -1)).map(String::strip).toList(); }
    private static String value(DefaultTableModel model, int row, int column) { Object value = model.getValueAt(row, column); return value == null ? "" : value.toString().strip(); }
    private static boolean stop(JTable table) { return !table.isEditing() || table.getCellEditor().stopCellEditing(); }
    private static JPanel form() { JPanel panel = new JPanel(); panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS)); return panel; }
    private static JTextArea text(String value, int rows) { JTextArea area = new JTextArea(value, rows, 45); area.setEditable(false); area.setLineWrap(true); area.setWrapStyleWord(true); area.setCaretPosition(0); return area; }
    private static void field(JPanel form, String label, JComponent component) {
        JLabel caption = new JLabel(label); caption.setLabelFor(component); form.add(caption);
        component.setMaximumSize(new Dimension(Integer.MAX_VALUE, component instanceof JScrollPane ? 110 : 32)); form.add(component); form.add(Box.createVerticalStrut(10));
    }
    private static JTable table(JPanel form, String label, DefaultTableModel model, Object[] blank) {
        form.add(new JLabel(label)); JTable table = new JTable(model); table.setRowHeight(28); table.setSurrendersFocusOnKeystroke(true);
        JScrollPane scroll = new JScrollPane(table); scroll.setPreferredSize(new Dimension(650, 150)); form.add(scroll);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        button(actions, "Add row", () -> { if (stop(table)) { model.addRow(blank); } });
        button(actions, "Remove selected row", () -> { if (stop(table) && table.getSelectedRow() >= 0) { model.removeRow(table.getSelectedRow()); } }); form.add(actions);
        return table;
    }
    private static void button(JPanel panel, String label, Runnable task) { JButton button = new JButton(label); button.addActionListener(event -> task.run()); panel.add(button); }
    private void selected(JTable table, List<Version> versions, Consumer<Version> action) {
        if (table.getSelectedRow() < 0) { message.setText("Select a CV version first."); }
        else { action.accept(versions.get(table.convertRowIndexToModel(table.getSelectedRow()))); }
    }
    private void refresh() { revalidate(); repaint(); }

    private <T> void work(Callable<T> task, Consumer<T> success) {
        if (busy) { return; }
        busy = true; enable(body, false); message.setText("Working… You can return to the overview while a file is being read.");
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return task.call(); }
            @Override protected void done() {
                busy = false; enable(body, true);
                try {
                    T result = get();
                    if (departed) {
                        if (result instanceof CvService.Draft abandoned) {
                            new SwingWorker<Void, Void>() { @Override protected Void doInBackground() { abandoned.close(); return null; } }.execute();
                        }
                        return;
                    }
                    message.setText("Ready"); success.accept(result);
                } catch (InterruptedException exception) { Thread.currentThread().interrupt(); message.setText("Interrupted. Try again."); }
                catch (java.util.concurrent.ExecutionException exception) {
                    message.setText(exception.getCause() instanceof UserError ? exception.getCause().getMessage() : "The operation failed. Check file access and the database, then try again.");
                }
            }
        }.execute();
    }
    private static void enable(Component component, boolean enabled) {
        component.setEnabled(enabled);
        if (component instanceof java.awt.Container container) { for (Component child : container.getComponents()) { enable(child, enabled); } }
    }
    private record Overview(CvProfile current, List<Version> history, List<Skill> skills, java.util.Map<UUID, smarthire.cv.CvApproval> approvals) { }
}
