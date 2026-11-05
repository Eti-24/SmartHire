package smarthire.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import smarthire.identity.Role;
import smarthire.identity.UserError;
import smarthire.jobs.Job;
import smarthire.jobs.JobService;
import smarthire.skills.Skill;
import static smarthire.jobs.Job.*;

/** Captures input on the EDT; every service operation runs on a worker. */
public final class JobsPanel extends JPanel {
    private final JobService service;
    private final UUID session;
    private final Role role;
    private final long initialJobId;
    private final smarthire.applications.ApplicationService applications;
    private final JPanel body = new JPanel(new BorderLayout(10, 10));
    private final JTextArea message = new JTextArea(3, 40);
    private final JButton back = new JButton("Back to overview");
    private boolean busy;
    private List<Category> categories = List.of();
    private List<Skill> skills = List.of();

    public JobsPanel(JobService service, UUID session, Role role, Runnable onBack) {
        this(service, session, role, onBack, 0);
    }

    public JobsPanel(JobService service, UUID session, Role role, Runnable onBack, long initialJobId) {
        this(service, session, role, onBack, initialJobId, null);
    }

    public JobsPanel(JobService service, UUID session, Role role, Runnable onBack, long initialJobId,
            smarthire.applications.ApplicationService applications) {
        super(new BorderLayout(10, 10));
        this.service = service; this.session = session; this.role = role;
        this.initialJobId = initialJobId;
        this.applications = applications;
        if (initialJobId != 0) { back.setText("Back to matches"); }
        back.addActionListener(event -> onBack.run());
        add(back, BorderLayout.NORTH); add(body, BorderLayout.CENTER);
        message.setEditable(false); message.setLineWrap(true); message.setWrapStyleWord(true);
        add(message, BorderLayout.SOUTH);
        load();
    }

    private void load() {
        work(() -> new Catalog(service.categories(session), service.skills(session)), catalog -> {
            categories = catalog.categories(); skills = catalog.skills();
            if (initialJobId == 0) { browse("", null); }
            else { work(() -> service.open(session, initialJobId), this::details); }
        });
    }

    private void browse(String query, Long categoryId) {
        work(() -> service.list(session, query, categoryId), jobs -> {
            body.removeAll();
            JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
            JTextField search = new JTextField(query, 14);
            JComboBox<Category> category = new JComboBox<>();
            category.addItem(new Category(0, "All categories"));
            for (Category item : categories) { category.addItem(item); if (categoryId != null && item.id() == categoryId) { category.setSelectedItem(item); } }
            controls.add(new JLabel("Title")); controls.add(search); controls.add(new JLabel("Category")); controls.add(category);
            button(controls, "Search", () -> browse(search.getText(), ((Category) category.getSelectedItem()).id() == 0 ? null : ((Category) category.getSelectedItem()).id()));
            button(controls, "Clear", () -> browse("", null));
            DefaultTableModel model = readOnly("ID", "Title", "Company", "Category", "Status");
            for (Job job : jobs) { model.addRow(new Object[]{job.id(), job.details().title(), job.companyName(), categoryName(job.details().categoryId()), job.status()}); }
            JTable table = new JTable(model); table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); table.setAutoCreateRowSorter(true);
            JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
            button(actions, "Open selected", () -> selected(table, jobs, job -> work(() -> service.open(session, job.id()), this::details)));
            if (role == Role.COMPANY) {
                button(actions, "Create job", () -> editor(null));
                button(actions, "Attach circular", () -> selected(table, jobs, this::attachCircular));
            }
            if (role == Role.ADMIN) {
                button(actions, "Verify companies", this::companies);
                button(actions, "Categories", this::categoryEditor);
                button(actions, "Skills", this::skillEditor);
                button(actions, "Matching weights", this::policyEditor);
            }
            body.add(controls, BorderLayout.NORTH); body.add(new JScrollPane(table), BorderLayout.CENTER); body.add(actions, BorderLayout.SOUTH);
            message.setText(jobs.isEmpty() ? "No jobs match these filters. Clear the filters to show all available jobs." : jobs.size() + " job(s). Open a posting to see its requirements.");
            refresh();
        });
    }

    private String categoryName(long id) { return categories.stream().filter(c -> c.id() == id).map(Category::name).findFirst().orElse("Unknown"); }

    private void details(Job job) {
        JPanel panel = form();
        Details d = job.details();
        JTextArea summary = new JTextArea(d.title() + "\n" + job.companyName() + " · " + categoryName(d.categoryId())
                + "\n" + d.location() + " · Salary " + d.salaryMin() + "–" + d.salaryMax() + "\nStatus: " + job.status()
                + "\n" + job.decision() + "\n\n" + d.description(), 9, 40);
        summary.setEditable(false); summary.setLineWrap(true); summary.setWrapStyleWord(true);
        panel.add(new JScrollPane(summary));
        JPanel circularControls = new JPanel(new java.awt.GridLayout(0, 1, 0, 8));
        circularControls.setBorder(BorderFactory.createTitledBorder("Job circular (optional PDF or DOCX)"));
        if (role == Role.COMPANY) {
            if (job.status() != Status.CLOSED) {
                button(circularControls, "Attach / replace circular", () -> attachCircular(job));
            } else { circularControls.add(new JLabel("This job is closed. Its circular cannot be changed.")); }
        }
        button(circularControls, "View circular", () -> work(() -> service.circular(session, job.id()), circular -> {
            if (circular.isEmpty()) { message.setText("This job has no circular attachment."); return; }
            var doc = circular.orElseThrow();
            var text = new JTextArea(doc.text(), 18, 55); text.setEditable(false); text.setLineWrap(true); text.setWrapStyleWord(true); text.setCaretPosition(0);
            JOptionPane.showMessageDialog(this, new JScrollPane(text), doc.originalName(), JOptionPane.PLAIN_MESSAGE);
        }));
        button(circularControls, "Save circular", () -> work(() -> service.circular(session, job.id()), circular -> {
            if (circular.isEmpty()) { message.setText("This job has no circular attachment."); return; }
            var chooser = new JFileChooser(); chooser.setSelectedFile(new java.io.File(circular.orElseThrow().originalName()));
            if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) { return; }
            var destination = chooser.getSelectedFile().toPath();
            if (java.nio.file.Files.exists(destination) && JOptionPane.showConfirmDialog(this, "Replace the existing file?", "Save circular", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) { return; }
            work(() -> { var bytes = service.downloadCircular(session, job.id()); java.nio.file.Files.write(destination, bytes); return true; }, ignored -> message.setText("Circular saved."));
        }));
        panel.add(circularControls);
        DefaultTableModel requirements = requirementModel(d.requirements(), false);
        panel.add(new JScrollPane(new JTable(requirements)));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        if (role == Role.STUDENT && applications != null) {
            button(actions, "Apply with my reviewed CV", () -> work(() -> applications.apply(session, job.id()),
                    submitted -> message.setText("Application submitted. Track it in My applications.")));
        }
        if (role == Role.COMPANY && job.status() != Status.CLOSED) {
            button(actions, "Edit and resubmit", () -> editor(job));
            button(actions, "Close job", () -> work(() -> { service.close(session, job.id()); return true; }, ignored -> browse("", null)));
        }
        if (role == Role.ADMIN && job.status() == Status.PENDING) {
            button(actions, "Approve", () -> work(() -> { service.moderate(session, job.id(), true, ""); return true; }, ignored -> browse("", null)));
            button(actions, "Reject", () -> {
                String reason = JOptionPane.showInputDialog(this, "Reason for rejection");
                if (reason != null) { work(() -> { service.moderate(session, job.id(), false, reason); return true; }, ignored -> browse("", null)); }
            });
        }
        button(actions, "Back to jobs", () -> browse("", null)); panel.add(actions);
        show(panel); message.setText("Required priority affects scoring. Only an explicit mandatory flag blocks eligibility.");
    }

    private void attachCircular(Job job) {
        if (job.status() == Status.CLOSED) { message.setText("Choose an open posting. Closed jobs cannot change their circular."); return; }
        var chooser = new JFileChooser();
        chooser.setDialogTitle("Attach a circular to " + job.details().title());
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Text PDF or DOCX (up to 5 MB)", "pdf", "docx"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) { return; }
        var source = chooser.getSelectedFile().toPath();
        work(() -> { service.attachCircular(session, job.id(), source); return service.open(session, job.id()); }, updated -> {
            details(updated); message.setText("Circular attached. This posting is now awaiting admin approval.");
        });
    }

    private void editor(Job job) {
        JPanel panel = form(); Details d = job == null ? null : job.details();
        JTextField title = new JTextField(d == null ? "" : d.title());
        JTextArea description = new JTextArea(d == null ? "" : d.description(), 4, 40);
        description.setLineWrap(true); description.setWrapStyleWord(true);
        JComboBox<Category> category = new JComboBox<>(categories.toArray(Category[]::new));
        if (d != null) { categories.stream().filter(c -> c.id() == d.categoryId()).findFirst().ifPresent(category::setSelectedItem); }
        JTextField location = new JTextField(d == null ? "" : d.location());
        JTextField min = new JTextField(d == null ? "0" : d.salaryMin().toPlainString());
        JTextField max = new JTextField(d == null ? "0" : d.salaryMax().toPlainString());
        field(panel, "Title", title); field(panel, "Description", new JScrollPane(description)); field(panel, "Category", category);
        field(panel, "Location", location); field(panel, "Salary minimum", min); field(panel, "Salary maximum", max);
        panel.add(new JLabel("Requirements: value = skill name, degree level, field or certification; months only for EXPERIENCE."));
        panel.add(new JLabel("Degree order: SECONDARY < DIPLOMA < BACHELOR < MASTER < DOCTORATE (demo convention)."));
        panel.add(new JLabel("Positive experience needs EXPERIENCE_TAG rows. Required priority alone is not mandatory."));
        DefaultTableModel model = requirementModel(d == null ? List.of() : d.requirements(), true);
        JTable table = new JTable(model); table.setRowHeight(25);
        table.getColumnModel().getColumn(0).setCellEditor(new DefaultCellEditor(new JComboBox<>(Kind.values())));
        JComboBox<String> values = new JComboBox<>(); values.setEditable(true);
        for (Skill skill : skills) { values.addItem(skill.name()); }
        for (Degree degree : Degree.values()) { values.addItem(degree.name()); }
        table.getColumnModel().getColumn(1).setCellEditor(new DefaultCellEditor(values));
        table.getColumnModel().getColumn(3).setCellEditor(new DefaultCellEditor(new JComboBox<>(Priority.values())));
        JScrollPane grid = new JScrollPane(table); grid.setPreferredSize(new Dimension(700, 180)); panel.add(grid);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        button(actions, "Suggest from description", () -> {
            if (table.isEditing() && !table.getCellEditor().stopCellEditing()) { return; }
            String source = description.getText(); work(() -> service.suggest(session, source), suggestions -> previewSuggestions(model, suggestions));
        });
        if (job != null) {
            button(actions, "Suggest from circular", () -> {
                if (table.isEditing() && !table.getCellEditor().stopCellEditing()) { return; }
                work(() -> service.suggest(session, service.circular(session, job.id()).orElseThrow(() -> new UserError("Attach a circular from the job details first.")).text()),
                        suggestions -> previewSuggestions(model, suggestions));
            });
        }
        button(actions, "Add requirement", () -> model.addRow(new Object[]{Kind.SKILL, "", 0, Priority.REQUIRED, false}));
        button(actions, "Remove row", () -> { if (table.getSelectedRow() >= 0) { model.removeRow(table.getSelectedRow()); } });
        button(actions, "Submit for approval", () -> {
            if (table.isEditing() && !table.getCellEditor().stopCellEditing()) { return; }
            try {
                if (category.getSelectedItem() == null) { throw new UserError("Ask an admin to create a category first."); }
                List<Requirement> items = new ArrayList<>();
                for (int row = 0; row < model.getRowCount(); row++) {
                    items.add(new Requirement((Kind) model.getValueAt(row, 0), String.valueOf(model.getValueAt(row, 1)),
                            Integer.parseInt(String.valueOf(model.getValueAt(row, 2))), (Priority) model.getValueAt(row, 3), (Boolean) model.getValueAt(row, 4)));
                }
                Details input = new Details(title.getText(), description.getText(), ((Category) category.getSelectedItem()).id(), location.getText(),
                        new BigDecimal(min.getText().strip()), new BigDecimal(max.getText().strip()), items);
                work(() -> service.submit(session, job == null ? 0 : job.id(), input), ignored -> browse("", null));
            } catch (NumberFormatException exception) { message.setText("Enter numeric salary values and whole-number months."); }
            catch (UserError exception) { message.setText(exception.getMessage()); }
        });
        button(actions, "Cancel", () -> browse("", null)); panel.add(actions); show(panel);
        message.setText("Saving returns the entire job to pending approval. Add at least one scored requirement.");
    }

    private void companies() {
        work(() -> service.companies(session), companies -> {
            JPanel panel = form(); JComboBox<Company> choice = new JComboBox<>(companies.toArray(Company[]::new));
            field(panel, "Company", choice);
            JCheckBox verified = new JCheckBox("Verified company"); panel.add(verified);
            choice.addActionListener(e -> { if (choice.getSelectedItem() instanceof Company c) { verified.setSelected(c.verified()); } });
            if (choice.getSelectedItem() instanceof Company c) { verified.setSelected(c.verified()); }
            button(panel, "Save verification", () -> {
                if (choice.getSelectedItem() instanceof Company c) {
                    boolean value = verified.isSelected(); work(() -> { service.verify(session, c.id(), value); return true; }, ignored -> companies());
                }
            });
            button(panel, "Back to jobs", () -> browse("", null)); show(panel);
            message.setText("Removing verification immediately hides this company's jobs from students.");
        });
    }

    private void previewSuggestions(DefaultTableModel target, smarthire.jobs.RequirementSuggestions suggestions) {
        var preview = new JPanel(new BorderLayout(8, 8));
        preview.add(new JScrollPane(new JTable(requirementModel(suggestions.requirements(), false))), BorderLayout.CENTER);
        var notes = new JTextArea(String.join("\n", suggestions.reviewNotes()), 5, 55); notes.setEditable(false); notes.setLineWrap(true); notes.setWrapStyleWord(true);
        preview.add(notes, BorderLayout.SOUTH);
        if (JOptionPane.showConfirmDialog(this, preview, "Review suggestions before adding", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) { return; }
        var seen = new java.util.HashSet<String>();
        for (int row = 0; row < target.getRowCount(); row++) { seen.add(requirementKey((Kind) target.getValueAt(row, 0), String.valueOf(target.getValueAt(row, 1)))); }
        int added = 0;
        for (var r : suggestions.requirements()) {
            if (seen.add(requirementKey(r.kind(), r.value()))) {
                target.addRow(new Object[]{r.kind(), displayRequirement(r), r.months(), r.priority(), false}); added++;
            }
        }
        message.setText(added + " suggestion(s) added for editing. Check the values, priority and mandatory flags before submitting.");
    }
    private String requirementKey(Kind kind, String value) {
        if (kind == Kind.DEGREE || kind == Kind.FIELD || kind == Kind.EXPERIENCE) { return kind.name(); }
        var vocabulary = new smarthire.skills.SkillVocabulary(skills);
        String canonical = vocabulary.findById(value).or(() -> vocabulary.resolve(value)).map(Skill::id)
                .orElse(java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC).toLowerCase(java.util.Locale.ROOT).replaceAll("(?U)\\s+", " ").strip());
        return kind + ":" + canonical;
    }

    private void policyEditor() {
        work(() -> service.scoringPolicy(session), policy -> {
            JPanel panel = form(); panel.add(new JLabel("Current policy: " + policy.version()));
            var inputs = new java.util.EnumMap<smarthire.matching.ScoringPolicy.Category, JTextField>(smarthire.matching.ScoringPolicy.Category.class);
            for (var category : smarthire.matching.ScoringPolicy.Category.values()) {
                var input = new JTextField(String.valueOf(policy.weights().getOrDefault(category, 0)));
                field(panel, category.name().toLowerCase(java.util.Locale.ROOT) + " weight", input); inputs.put(category, input);
            }
            panel.add(new JLabel("Weights are normalized for each job's active categories. Original application scores stay unchanged."));
            button(panel, "Activate weights", () -> {
                try {
                    var weights = new java.util.EnumMap<smarthire.matching.ScoringPolicy.Category, Integer>(smarthire.matching.ScoringPolicy.Category.class);
                    inputs.forEach((category, input) -> weights.put(category, Integer.parseInt(input.getText().strip())));
                    work(() -> service.activatePolicy(session, weights), activated -> { policyEditor(); message.setText("Policy activated. Matches and applicant rankings recalculate on refresh."); });
                } catch (NumberFormatException e) { message.setText("Enter nonnegative whole-number weights with a positive total."); }
            });
            button(panel, "Back to jobs", () -> browse("", null)); show(panel);
            message.setText("Activation checks all approved open jobs and all jobs with applications, including closed postings.");
        });
    }

    private void categoryEditor() {
        JPanel panel = form(); JComboBox<Category> choice = new JComboBox<>(); choice.addItem(new Category(0, "New category")); categories.forEach(choice::addItem);
        JTextField name = new JTextField(); field(panel, "Category", choice); field(panel, "Name", name);
        choice.addActionListener(e -> name.setText(((Category) choice.getSelectedItem()).id() == 0 ? "" : ((Category) choice.getSelectedItem()).name()));
        button(panel, "Save category", () -> { long id = ((Category) choice.getSelectedItem()).id(); String value = name.getText(); work(() -> service.saveCategory(session, id, value), ignored -> load()); });
        button(panel, "Back to jobs", () -> browse("", null)); show(panel);
    }

    private void skillEditor() {
        JPanel panel = form(); JComboBox<String> choice = new JComboBox<>(); choice.addItem("New skill"); skills.forEach(s -> choice.addItem(s.id()));
        JTextField id = new JTextField(), name = new JTextField(), aliases = new JTextField();
        JComboBox<Skill.Type> type = new JComboBox<>(Skill.Type.values());
        field(panel, "Skill", choice); field(panel, "Stable ID (lowercase, hyphens allowed)", id); field(panel, "Display name", name);
        field(panel, "Type", type); field(panel, "Aliases (comma separated)", aliases);
        choice.addActionListener(e -> {
            Skill selected = skills.stream().filter(s -> s.id().equals(choice.getSelectedItem())).findFirst().orElse(null);
            id.setEditable(selected == null); id.setText(selected == null ? "" : selected.id()); name.setText(selected == null ? "" : selected.name());
            aliases.setText(selected == null ? "" : String.join(", ", selected.aliases())); type.setSelectedItem(selected == null ? Skill.Type.TECHNICAL : selected.type());
        });
        button(panel, "Save skill", () -> {
            try {
                Skill skill = new Skill(id.getText().strip(), name.getText(), (Skill.Type) type.getSelectedItem(),
                        aliases.getText().isBlank() ? Set.of() : Set.copyOf(Arrays.stream(aliases.getText().split(",", -1)).map(String::strip).toList()));
                work(() -> { service.saveSkill(session, skill); return true; }, ignored -> load());
            } catch (IllegalArgumentException exception) { message.setText(exception.getMessage()); }
        });
        button(panel, "Back to jobs", () -> browse("", null)); show(panel);
        message.setText("Canonical IDs stay stable when names or aliases change. Existing requirements retain those IDs.");
    }

    private DefaultTableModel requirementModel(List<Requirement> items, boolean editable) {
        DefaultTableModel model = new DefaultTableModel(new Object[]{"Kind", "Value", "Months", "Priority", "Mandatory"}, 0) {
            @Override public boolean isCellEditable(int row, int column) { return editable; }
            @Override public Class<?> getColumnClass(int column) { return column == 4 ? Boolean.class : Object.class; }
        };
        for (Requirement r : items) { model.addRow(new Object[]{r.kind(), displayRequirement(r), r.months(), r.priority(), r.mandatory()}); }
        return model;
    }
    private String displayRequirement(Requirement r) {
        return switch (r.kind()) {
            case SKILL, PROJECT, EXPERIENCE_TAG -> skills.stream().filter(s -> s.id().equals(r.value())).map(Skill::name).findFirst().orElse(r.value());
            default -> r.value();
        };
    }
    private static DefaultTableModel readOnly(String... columns) { return new DefaultTableModel(columns, 0) { @Override public boolean isCellEditable(int r, int c) { return false; } }; }
    private void selected(JTable table, List<Job> jobs, Consumer<Job> action) { if (table.getSelectedRow() < 0) { message.setText("Select a job first."); } else { action.accept(jobs.get(table.convertRowIndexToModel(table.getSelectedRow()))); } }
    private static JPanel form() { JPanel p = new JPanel(); p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS)); return p; }
    private static void field(JPanel p, String label, JComponent input) { JLabel caption = new JLabel(label); caption.setLabelFor(input); p.add(caption); input.setMaximumSize(new Dimension(Integer.MAX_VALUE, input instanceof JScrollPane ? 100 : 30)); p.add(input); p.add(Box.createVerticalStrut(8)); }
    private static void button(JPanel p, String label, Runnable action) { JButton b = new JButton(label); b.addActionListener(e -> action.run()); p.add(b); }
    private void show(JPanel panel) { body.removeAll(); body.add(new JScrollPane(panel)); refresh(); }
    private void refresh() { revalidate(); repaint(); }
    private <T> void work(Callable<T> task, Consumer<T> success) {
        if (busy) { return; } busy = true; enable(this, false); message.setText("Working…");
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return task.call(); }
            @Override protected void done() {
                busy = false; enable(JobsPanel.this, true);
                try { T result = get(); message.setText("Ready"); success.accept(result); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); message.setText("Interrupted. Try again."); }
                catch (java.util.concurrent.ExecutionException e) { message.setText(e.getCause() instanceof UserError ? e.getCause().getMessage() : "The operation failed. Check the database and try again."); }
            }
        }.execute();
    }
    private static void enable(Component c, boolean enabled) { c.setEnabled(enabled); if (c instanceof java.awt.Container container) { for (Component child : container.getComponents()) { enable(child, enabled); } } }
    private record Catalog(List<Category> categories, List<Skill> skills) { }
}
